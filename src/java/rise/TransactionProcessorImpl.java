package rise;

import rise.db.DbClause;
import rise.db.DbIterator;
import rise.db.DbKey;
import rise.db.EntityDbTable;
import rise.peer.Peer;
import rise.peer.Peers;
import rise.util.JSON;
import rise.util.Listener;
import rise.util.Listeners;
import rise.util.Logger;
import rise.util.ThreadPool;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TransactionProcessorImpl implements TransactionProcessor {

    private static final boolean enableTransactionRebroadcasting = Rise.getBooleanProperty("rise.enableTransactionRebroadcasting");
    private static final boolean testUnconfirmedTransactions = Rise.getBooleanProperty("rise.testUnconfirmedTransactions");

    private static final TransactionProcessorImpl instance = new TransactionProcessorImpl();

    static TransactionProcessorImpl getInstance() {
        return instance;
    }

    final DbKey.LongKeyFactory<TransactionImpl> unconfirmedTransactionDbKeyFactory = new DbKey.LongKeyFactory<TransactionImpl>("id") {

        @Override
        public DbKey newKey(TransactionImpl transaction) {
            return transaction.getDbKey();
        }

    };

    private final EntityDbTable<TransactionImpl> unconfirmedTransactionTable = new EntityDbTable<TransactionImpl>("unconfirmed_transaction", unconfirmedTransactionDbKeyFactory) {

        @Override
        protected TransactionImpl load(Connection con, ResultSet rs) throws SQLException {
            byte[] transactionBytes = rs.getBytes("transaction_bytes");
            try {
                TransactionImpl transaction = TransactionImpl.parseTransaction(transactionBytes);
                transaction.setHeight(rs.getInt("transaction_height"));
                return transaction;
            } catch (RiseException.ValidationException e) {
                throw new RuntimeException(e.toString(), e);
            }
        }

        @Override
        protected void save(Connection con, TransactionImpl transaction) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO unconfirmed_transaction (id, transaction_height, "
                    + "fee_per_byte, timestamp, expiration, transaction_bytes, height) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                int i = 0;
                pstmt.setLong(++i, transaction.getId());
                pstmt.setInt(++i, transaction.getHeight());
                pstmt.setLong(++i, transaction.getFeeNQT() / transaction.getSize());
                pstmt.setInt(++i, transaction.getTimestamp());
                pstmt.setInt(++i, transaction.getExpiration());
                pstmt.setBytes(++i, transaction.getBytes());
                pstmt.setInt(++i, Rise.getBlockchain().getHeight());
                pstmt.executeUpdate();
            }
        }

        @Override
        public void rollback(int height) {
            List<TransactionImpl> transactions = new ArrayList<>();
            try (Connection con = Db.db.getConnection();
                 PreparedStatement pstmt = con.prepareStatement("SELECT * FROM unconfirmed_transaction WHERE height > ?")) {
                pstmt.setInt(1, height);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        transactions.add(load(con, rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e.toString(), e);
            }
            super.rollback(height);
            processLater(transactions);
        }

        @Override
        protected String defaultSort() {
            return " ORDER BY transaction_height ASC, fee_per_byte DESC, timestamp ASC, id ASC ";
        }

    };

    private final Set<TransactionImpl> broadcastedTransactions = Collections.newSetFromMap(new ConcurrentHashMap<TransactionImpl,Boolean>());
    private final Listeners<List<? extends Transaction>,Event> transactionListeners = new Listeners<>();
    private final Set<TransactionImpl> lostTransactions = new HashSet<>();

    private final Runnable removeUnconfirmedTransactionsThread = new Runnable() {

        private final DbClause expiredClause = new DbClause(" expiration < ? ") {
            @Override
            protected int set(PreparedStatement pstmt, int index) throws SQLException {
                pstmt.setInt(index, Rise.getEpochTime());
                return index + 1;
            }
        };

        @Override
        public void run() {

            try {
                try {
                    List<TransactionImpl> expiredTransactions = new ArrayList<>();
                    try (DbIterator<TransactionImpl> iterator = unconfirmedTransactionTable.getManyBy(expiredClause, 0, -1, "")) {
                        while (iterator.hasNext()) {
                            expiredTransactions.add(iterator.next());
                        }
                    }
                    if (expiredTransactions.size() > 0) {
                        synchronized (BlockchainImpl.getInstance()) {
                            try {
                                Db.db.beginTransaction();
                                for (TransactionImpl transaction : expiredTransactions) {
                                    removeUnconfirmedTransaction(transaction);
                                }
                                Db.db.commitTransaction();
                            } catch (Exception e) {
                                Logger.logErrorMessage(e.toString(), e);
                                Db.db.rollbackTransaction();
                                throw e;
                            } finally {
                                Db.db.endTransaction();
                            }
                        } // synchronized
                    }
                } catch (Exception e) {
                    Logger.logDebugMessage("Error removing unconfirmed transactions", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    private final Runnable rebroadcastTransactionsThread = new Runnable() {

        @Override
        public void run() {

            try {
                try {
                    List<Transaction> transactionList = new ArrayList<>();
                    int curTime = Rise.getEpochTime();
                    for (TransactionImpl transaction : broadcastedTransactions) {
                        if (TransactionDb.hasTransaction(transaction.getId()) || transaction.getExpiration() < curTime) {
                            broadcastedTransactions.remove(transaction);
                        } else if (transaction.getTimestamp() < curTime - 30) {
                            transactionList.add(transaction);
                        }
                    }

                    if (transactionList.size() > 0) {
                        Peers.sendToSomePeers(transactionList);
                    }

                } catch (Exception e) {
                    Logger.logDebugMessage("Error in transaction re-broadcasting thread", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    private final Runnable processTransactionsThread = new Runnable() {

        private final JSONStreamAware getUnconfirmedTransactionsRequest;
        {
            JSONObject request = new JSONObject();
            request.put("requestType", "getUnconfirmedTransactions");
            getUnconfirmedTransactionsRequest = JSON.prepareRequest(request);
        }

        @Override
        public void run() {
            try {
                try {
                    synchronized (BlockchainImpl.getInstance()) {
                        processTransactions(lostTransactions, false);
                        lostTransactions.clear();
                    }
                    Peer peer = Peers.getAnyPeer(Peer.State.CONNECTED, true);
                    if (peer == null) {
                        return;
                    }
                    JSONObject response = peer.send(getUnconfirmedTransactionsRequest);
                    if (response == null) {
                        return;
                    }
                    JSONArray transactionsData = (JSONArray)response.get("unconfirmedTransactions");
                    if (transactionsData == null || transactionsData.size() == 0) {
                        return;
                    }
                    try {
                        processPeerTransactions(transactionsData);
                    } catch (RiseException.ValidationException|RuntimeException e) {
                        peer.blacklist(e);
                    }
                } catch (Exception e) {
                    Logger.logDebugMessage("Error processing unconfirmed transactions", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }
        }

    };

    private TransactionProcessorImpl() {
        ThreadPool.scheduleThread("ProcessTransactions", processTransactionsThread, 5);
        ThreadPool.scheduleThread("RemoveUnconfirmedTransactions", removeUnconfirmedTransactionsThread, 1);
        if (enableTransactionRebroadcasting) {
            ThreadPool.scheduleThread("RebroadcastTransactions", rebroadcastTransactionsThread, 60);
            ThreadPool.runAfterStart(new Runnable() {
                @Override
                public void run() {
                    try (DbIterator<TransactionImpl> oldNonBroadcastedTransactions = getAllUnconfirmedTransactions()) {
                        for (TransactionImpl transaction : oldNonBroadcastedTransactions) {
                            broadcastedTransactions.add(transaction);
                        }
                    }
                }
            });
        }
    }

    @Override
    public boolean addListener(Listener<List<? extends Transaction>> listener, Event eventType) {
        return transactionListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<List<? extends Transaction>> listener, Event eventType) {
        return transactionListeners.removeListener(listener, eventType);
    }

    void notifyListeners(List<? extends Transaction> transactions, Event eventType) {
        transactionListeners.notify(transactions, eventType);
    }

    @Override
    public DbIterator<TransactionImpl> getAllUnconfirmedTransactions() {
        return unconfirmedTransactionTable.getAll(0, -1);
    }

    @Override
    public Transaction getUnconfirmedTransaction(long transactionId) {
        return unconfirmedTransactionTable.get(unconfirmedTransactionDbKeyFactory.newKey(transactionId));
    }

    public Transaction.Builder newTransactionBuilder(byte[] senderPublicKey, long amountNQT, long feeNQT, short deadline,
                                                     Attachment attachment) {
        byte version = (byte) getTransactionVersion(Rise.getBlockchain().getHeight());
        int timestamp = Rise.getEpochTime();
        TransactionImpl.BuilderImpl builder = new TransactionImpl.BuilderImpl(version, senderPublicKey, amountNQT, feeNQT, timestamp,
                deadline, (Attachment.AbstractAttachment)attachment);
        if (version > 0) {
            Block ecBlock = EconomicClustering.getECBlock(timestamp);
            builder.ecBlockHeight(ecBlock.getHeight());
            builder.ecBlockId(ecBlock.getId());
        }
        return builder;
    }

    @Override
    public void broadcast(Transaction transaction) throws RiseException.ValidationException {
        if (! transaction.verifySignature()) {
            throw new RiseException.NotValidException("Transaction signature verification failed");
        }
        List<Transaction> processedTransactions;
        synchronized (BlockchainImpl.getInstance()) {
            if (TransactionDb.hasTransaction(transaction.getId())) {
                Logger.logMessage("Transaction " + transaction.getStringId() + " already in blockchain, will not broadcast again");
                return;
            }
            if (unconfirmedTransactionTable.get(((TransactionImpl) transaction).getDbKey()) != null) {
                if (enableTransactionRebroadcasting) {
                    broadcastedTransactions.add((TransactionImpl) transaction);
                    Logger.logMessage("Transaction " + transaction.getStringId() + " already in unconfirmed pool, will re-broadcast");
                } else {
                    Logger.logMessage("Transaction " + transaction.getStringId() + " already in unconfirmed pool, will not broadcast again");
                }
                return;
            }
            processedTransactions = processTransactions(Collections.singleton((TransactionImpl) transaction), true);
        }
        if (processedTransactions.contains(transaction)) {
            if (enableTransactionRebroadcasting) {
                broadcastedTransactions.add((TransactionImpl) transaction);
            }
            Logger.logDebugMessage("Accepted new transaction " + transaction.getStringId());
        } else {
            Logger.logDebugMessage("Could not accept new transaction " + transaction.getStringId());
            throw new RiseException.NotValidException("Invalid transaction " + transaction.getStringId());
        }
    }

    @Override
    public void processPeerTransactions(JSONObject request) throws RiseException.ValidationException {
        JSONArray transactionsData = (JSONArray)request.get("transactions");
        processPeerTransactions(transactionsData);
    }

    @Override
    public Transaction parseTransaction(byte[] bytes) throws RiseException.ValidationException {
        return TransactionImpl.parseTransaction(bytes);
    }

    @Override
    public TransactionImpl parseTransaction(JSONObject transactionData) throws RiseException.NotValidException {
        return TransactionImpl.parseTransaction(transactionData);
    }

    @Override
    public void clearUnconfirmedTransactions() {
        synchronized (BlockchainImpl.getInstance()) {
            List<Transaction> removed = new ArrayList<>();
            try {
                Db.db.beginTransaction();
                try (DbIterator<TransactionImpl> unconfirmedTransactions = getAllUnconfirmedTransactions()) {
                    for (TransactionImpl transaction : unconfirmedTransactions) {
                        transaction.undoUnconfirmed();
                        removed.add(transaction);
                    }
                }
                unconfirmedTransactionTable.truncate();
                Db.db.commitTransaction();
            } catch (Exception e) {
                Logger.logErrorMessage(e.toString(), e);
                Db.db.rollbackTransaction();
                throw e;
            } finally {
                Db.db.endTransaction();
            }
            transactionListeners.notify(removed, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        }
    }

    void requeueAllUnconfirmedTransactions() {
        List<Transaction> removed = new ArrayList<>();
        try (DbIterator<TransactionImpl> unconfirmedTransactions = getAllUnconfirmedTransactions()) {
            for (TransactionImpl transaction : unconfirmedTransactions) {
                transaction.undoUnconfirmed();
                removed.add(transaction);
                lostTransactions.add(transaction);
            }
        }
        unconfirmedTransactionTable.truncate();
        transactionListeners.notify(removed, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
    }

    void removeUnconfirmedTransaction(TransactionImpl transaction) {
        if (!Db.db.isInTransaction()) {
            try {
                Db.db.beginTransaction();
                removeUnconfirmedTransaction(transaction);
                Db.db.commitTransaction();
            } catch (Exception e) {
                Logger.logErrorMessage(e.toString(), e);
                Db.db.rollbackTransaction();
                throw e;
            } finally {
                Db.db.endTransaction();
            }
            return;
        }
        try (Connection con = Db.db.getConnection();
             PreparedStatement pstmt = con.prepareStatement("DELETE FROM unconfirmed_transaction WHERE id = ?")) {
            pstmt.setLong(1, transaction.getId());
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                transaction.undoUnconfirmed();
                transactionListeners.notify(Collections.singletonList(transaction), Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
            }
        } catch (SQLException e) {
            Logger.logErrorMessage(e.toString(), e);
            throw new RuntimeException(e.toString(), e);
        }
    }

    int getTransactionVersion(int previousBlockHeight) {
        return previousBlockHeight < Constants.DIGITAL_GOODS_STORE_BLOCK ? 0 : 1;
    }

    void processLater(Collection<TransactionImpl> transactions) {
        synchronized (BlockchainImpl.getInstance()) {
            for (TransactionImpl transaction : transactions) {
                lostTransactions.add(transaction);
            }
        }
    }

    private void processPeerTransactions(JSONArray transactionsData) throws RiseException.ValidationException {
        if (Rise.getBlockchain().getLastBlock().getTimestamp() < Rise.getEpochTime() - 60 * 1440 && ! testUnconfirmedTransactions) {
            return;
        }
        if (Rise.getBlockchain().getHeight() <= Constants.NQT_BLOCK) {
            return;
        }
        List<TransactionImpl> transactions = new ArrayList<>();
        for (Object transactionData : transactionsData) {
            try {
                TransactionImpl transaction = parseTransaction((JSONObject) transactionData);
                transaction.validate();
                transactions.add(transaction);
            } catch (RiseException.NotCurrentlyValidException ignore) {
            } catch (RiseException.NotValidException e) {
                Logger.logDebugMessage("Invalid transaction from peer: " + ((JSONObject) transactionData).toJSONString());
                throw e;
            }
        }
        processTransactions(transactions, true);
        broadcastedTransactions.removeAll(transactions);
    }

    List<Transaction> processTransactions(Collection<TransactionImpl> transactions, final boolean sendToPeers) {
        if (transactions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Transaction> sendToPeersTransactions = new ArrayList<>();
        List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
        List<Transaction> addedDoubleSpendingTransactions = new ArrayList<>();

        for (TransactionImpl transaction : transactions) {

            try {

                int curTime = Rise.getEpochTime();
                if (transaction.getTimestamp() > curTime + 15 || transaction.getExpiration() < curTime
                        || transaction.getDeadline() > 1440) {
                    continue;
                }
                if (transaction.getVersion() < 1) {
                    continue;
                }

                synchronized (BlockchainImpl.getInstance()) {
                    try {
                        Db.db.beginTransaction();
                        if (Rise.getBlockchain().getHeight() < Constants.NQT_BLOCK) {
                            break; // not ready to process transactions
                        }

                        if (TransactionDb.hasTransaction(transaction.getId()) || unconfirmedTransactionTable.get(transaction.getDbKey()) != null) {
                            continue;
                        }

                        if (! transaction.verifySignature()) {
                            if (Account.getAccount(transaction.getSenderId()) != null) {
                                Logger.logDebugMessage("Transaction " + transaction.getJSONObject().toJSONString() + " failed to verify");
                            }
                            continue;
                        }

                        if (transaction.applyUnconfirmed()) {
                            if (sendToPeers) {
                                if (broadcastedTransactions.contains(transaction)) {
                                    Logger.logDebugMessage("Received back transaction " + transaction.getStringId()
                                            + " that we generated, will not forward to peers");
                                    broadcastedTransactions.remove(transaction);
                                } else {
                                    sendToPeersTransactions.add(transaction);
                                }
                            }
                            unconfirmedTransactionTable.insert(transaction);
                            addedUnconfirmedTransactions.add(transaction);
                        } else {
                            addedDoubleSpendingTransactions.add(transaction);
                        }
                        Db.db.commitTransaction();
                    } catch (Exception e) {
                        Db.db.rollbackTransaction();
                        throw e;
                    } finally {
                        Db.db.endTransaction();
                    }
                }
            } catch (RuntimeException e) {
                Logger.logMessage("Error processing transaction", e);
            }

        }

        if (sendToPeersTransactions.size() > 0) {
            Peers.sendToSomePeers(sendToPeersTransactions);
        }

        if (addedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(addedUnconfirmedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
        }
        if (addedDoubleSpendingTransactions.size() > 0) {
            transactionListeners.notify(addedDoubleSpendingTransactions, Event.ADDED_DOUBLESPENDING_TRANSACTIONS);
        }
        return addedUnconfirmedTransactions;
    }

}
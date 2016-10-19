package piuk.blockchain.android.data.stores;

import info.blockchain.wallet.payload.Tx;
import info.blockchain.wallet.payload.TxMostRecentDateComparator;

import java.util.List;

public class TransactionListStore extends ListStore<Tx> {

    public TransactionListStore() {
        // Empty constructor
    }

    public void insertTransactionIntoListAndSort(Tx transaction) {
        insertObjectIntoList(transaction);
        sort(new TxMostRecentDateComparator());
    }

    public void insertTransactions(List<Tx> transactions) {
        insertBulk(transactions);
    }
}

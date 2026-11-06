package com.andrey.cashflow

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class Saver(private val storage: Storage, private val network: () -> Boolean) : Sink {

    private sealed interface Op
    private class Created(val account: Account) : Op
    private class Changed(val account: Account, val entry: Entry) : Op
    private class Updated(val account: Account) : Op
    private class Deleted(val account: Account) : Op
    private class Noticed(val notice: Notice) : Op

    private val queue = ConcurrentLinkedQueue<Op>()
    private val pending = ArrayList<Op>()

    override fun created(account: Account) { queue += Created(account) }

    override fun changed(account: Account, entry: Entry) { queue += Changed(account, entry) }

    override fun updated(account: Account) { queue += Updated(account) }

    override fun deleted(account: Account) { queue += Deleted(account) }

    fun notice(notice: Notice) { queue += Noticed(notice) }

    @Synchronized
    fun flush() {
        while (true) pending += queue.poll() ?: break
        if (pending.isEmpty()) return
        storage.write(batch(), network())
        pending.clear()
    }

    private fun batch(): Batch {
        val deleted = LinkedHashSet<Account>()
        val accounts = LinkedHashSet<Account>()
        val changes = LinkedHashMap<Account, Long>()
        val entries = ArrayList<Entry>()
        val notices = ArrayList<Notice>()
        for (op in pending) {
            when (op) {
                is Created -> accounts += op.account
                is Updated -> accounts += op.account
                is Changed -> {
                    changes.merge(op.account, op.entry.amount, Long::plus)
                    entries += op.entry
                }
                is Deleted -> deleted += op.account
                is Noticed -> notices += op.notice
            }
        }
        accounts.removeAll(deleted)
        deleted.forEach(changes::remove)
        val deletedIds: Collection<UUID> = deleted.mapTo(LinkedHashSet()) { it.id }
        return Batch(deletedIds, accounts, changes, entries, notices)
    }
}

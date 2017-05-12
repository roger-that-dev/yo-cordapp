package net.corda.yo

import com.google.common.net.HostAndPort
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.loggerFor
import org.slf4j.Logger
import rx.Observable

fun main(args: Array<String>) {
    YoRPC().main(args)
}

private class YoRPC {
    companion object {
        val logger: Logger = loggerFor<YoRPC>()
    }

    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: YoRPC <node address:port>" }
        val nodeAddress = HostAndPort.fromString(args[0])
        val client = CordaRPCClient(nodeAddress)
        // Can be amended in the com.template.MainKt file.
        client.start("user1", "test")
        val proxy = client.proxy()
        // Grab all signed transactions and all future signed transactions.
        val (transactions: List<SignedTransaction>, futureTransactions: Observable<SignedTransaction>) =
                proxy.verifiedTransactions()
        // Log the existing Yo's and listen for new ones.
        futureTransactions.startWith(transactions).toBlocking().subscribe { transaction ->
            transaction.tx.outputs.forEach { output ->
                val state = output.data as Yo.State
                logger.info(state.toString())
            }
        }
    }
}

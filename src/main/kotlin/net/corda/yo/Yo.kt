package net.corda.yo

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.notUsed
import net.corda.core.contracts.*
import net.corda.core.crypto.Party
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.getOrThrow
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.node.PluginServiceHub
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.FinalityFlow
import rx.Observable
import java.security.PublicKey
import java.util.function.Function
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

// API.
@Path("yo")
class YoApi(val services: CordaRPCOps) {
    // Helper extension property to grab snapshot only.
    private val <A> Pair<A, Observable<*>>.justSnapshot: A get() {
        second.notUsed()
        return first
    }

    @GET
    @Path("yo")
    @Produces(MediaType.APPLICATION_JSON)
    fun yo(@QueryParam(value = "target") target: String): Response {
        val (status, message) = try {
            // Is the 'target' valid?
            val toYo = services.partyFromName(target) ?: throw IllegalArgumentException("$target is unknown.")
            // Start the flow.
            val flowHandle = services.startFlowDynamic(YoFlow::class.java, toYo)
            flowHandle.use { it.returnValue.getOrThrow() }
            // Return the response.
            Response.Status.CREATED to "Yo just send a Yo! to ${toYo.name}"
        } catch (e: Exception) {
            Response.Status.BAD_REQUEST to e.message
        }
        return Response.status(status).entity(message).build()
    }

    @GET
    @Path("yos")
    @Produces(MediaType.APPLICATION_JSON)
    fun yos() = services.vaultAndUpdates().justSnapshot.filter { it.state.data is Yo.State }

    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun me() = mapOf("me" to services.nodeIdentity().legalIdentity.name)

    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    fun peers() = mapOf("peers" to services.networkMapUpdates().justSnapshot.map { it.legalIdentity.name })
}

// Flow.
class YoFlow(val target: Party): FlowLogic<SignedTransaction>() {

    override val progressTracker: ProgressTracker = YoFlow.tracker()

    companion object {
        object CREATING : ProgressTracker.Step("Creating a new Yo!")
        object VERIFYING : ProgressTracker.Step("Verifying the Yo!")
        object SENDING : ProgressTracker.Step("Sending the Yo!")
        fun tracker() = ProgressTracker(CREATING, VERIFYING, SENDING)
    }

    @Suspendable
    override fun call(): SignedTransaction {
        val me = serviceHub.myInfo.legalIdentity
        val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

        progressTracker.currentStep = CREATING
        val signedYo = TransactionType.General.Builder(notary)
                .withItems(Yo.State(me, target), Command(Yo.Send(), listOf(me.owningKey)))
                .signWith(serviceHub.legalIdentityKey)
                .toSignedTransaction(true)

        progressTracker.currentStep = VERIFYING
        signedYo.tx.toLedgerTransaction(serviceHub).verify()

        progressTracker.currentStep = SENDING
        return subFlow(FinalityFlow(signedYo, setOf(target))).single()
    }
}

// Contract and state.
class Yo : Contract {

    // Command.
    class Send : TypeOnlyCommandData()

    // Legal prose.
    override val legalContractReference: SecureHash = SecureHash.sha256("Yo!")

    // Contract code.
    override fun verify(tx: TransactionForContract) = requireThat {
        val command = tx.commands.requireSingleCommand<Send>()
        "There can be no inputs when Yo'ing other parties." using (tx.inputs.isEmpty())
        "There must be one output: The Yo!" using (tx.outputs.size == 1)
        val yo = tx.outputs.single() as Yo.State
        "No sending Yo's to yourself!" using (yo.target != yo.origin)
        "The Yo! must be signed by the sender." using (yo.origin.owningKey == command.signers.single())
    }

    // State.
    data class State(val origin: Party,
                     val target: Party,
                     val yo: String = "Yo!",
                     override val linearId: UniqueIdentifier = UniqueIdentifier()): LinearState {
        override val participants get() = listOf(target.owningKey)
        override val contract get() = Yo()
        override fun isRelevant(ourKeys: Set<PublicKey>) = ourKeys.intersect(participants).isNotEmpty()
        override fun toString() = "${origin.name}: $yo"
    }
}

// Plugin.
class YoPlugin : CordaPluginRegistry() {
    override val webApis = listOf(Function(::YoApi))
    override val requiredFlows = mapOf(YoFlow::class.java.name to setOf(Party::class.java.name))
    override val servicePlugins: List<Function<PluginServiceHub, out Any>> = listOf()
    override val staticServeDirs = mapOf("yo" to javaClass.classLoader.getResource("yoWeb").toExternalForm())
}
package net.corda.yo

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.notUsed
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.getOrThrow
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.FinalityFlow
import net.corda.webserver.services.WebServerPluginRegistry
import org.bouncycastle.asn1.x500.X500Name
import rx.Observable
import java.util.function.Function
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table
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
            val toYo = services.partyFromX500Name(X500Name(target)) ?: throw Exception("Party not recognised.")
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
    fun yos() = services.vaultQuery(Yo.State::class.java).states

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
@InitiatingFlow
@StartableByRPC
class YoFlow(val target: Party): FlowLogic<SignedTransaction>() {

    override val progressTracker: ProgressTracker = YoFlow.tracker()

    companion object {
        object CREATING : ProgressTracker.Step("Creating a new Yo!")
        object SIGNING : ProgressTracker.Step("Verifying the Yo!")
        object VERIFYING : ProgressTracker.Step("Verifying the Yo!")
        object FINALISING : ProgressTracker.Step("Sending the Yo!") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(CREATING, SIGNING, VERIFYING, FINALISING)
    }

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = CREATING

        val me = serviceHub.myInfo.legalIdentity
        val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
        val command = Command(Yo.Send(), listOf(me.owningKey))
        val state = Yo.State(me, target)
        val utx = TransactionBuilder(notary = notary).withItems(state, command)

        progressTracker.currentStep = SIGNING
        val stx = serviceHub.signInitialTransaction(utx)

        progressTracker.currentStep = VERIFYING
        stx.tx.toLedgerTransaction(serviceHub).verify()

        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker())).single()
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
                     val yo: String = "Yo!") : ContractState, QueryableState {
        override val participants get() = listOf(target)
        override val contract get() = Yo()
        override fun toString() = "${origin.name}: $yo"
        override fun supportedSchemas() = listOf(YoSchemaV1)
        override fun generateMappedObject(schema: MappedSchema) = YoSchemaV1.YoEntity(this)

        object YoSchemaV1 : MappedSchema(Yo.State::class.java, 1, listOf(YoEntity::class.java)) {
            @Entity @Table(name = "yos")
            class YoEntity(yo: State) : PersistentState() {
                @Column var origin: String = yo.origin.name.toString()
                @Column var target: String = yo.target.name.toString()
                @Column var yo: String = yo.yo
            }
        }
    }
}

// Plugin.
class YoPlugin : CordaPluginRegistry() {
    override val requiredSchemas: Set<MappedSchema> = setOf(Yo.State.YoSchemaV1)
}

class YoWebPlugin : WebServerPluginRegistry {
    override val webApis = listOf(Function(::YoApi))
    override val staticServeDirs = mapOf("yo" to javaClass.classLoader.getResource("yoWeb").toExternalForm())
}

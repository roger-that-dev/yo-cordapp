package net.corda.yo

import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria.VaultCustomQueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.*
import net.corda.testing.contracts.DUMMY_PROGRAM_ID
import net.corda.testing.contracts.DummyState
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import net.corda.yo.YoState.YoSchemaV1.PersistentYoState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class YoFlowTests {
    lateinit var net: MockNetwork
    lateinit var a: StartedNode<MockNode>
    lateinit var b: StartedNode<MockNode>

    @Before
    fun setup() {
        setCordappPackages("net.corda.yo")
        net = MockNetwork()
        val nodes = net.createSomeNodes(2)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]
        net.runNetwork()
    }

    @After
    fun tearDown() {
        unsetCordappPackages()
        net.stopNodes()
    }

    @Test
    fun flowWorksCorrectly() {
        val yo = YoState(a.info.legalIdentities.first(), b.info.legalIdentities.first())
        val flow = YoFlow(b.info.legalIdentities.first())
        val future = a.services.startFlow(flow).resultFuture
        net.runNetwork()
        val stx = future.getOrThrow()
        // Check yo transaction is stored in the storage service.
        val bTx = b.services.validatedTransactions.getTransaction(stx.id)
        assertEquals(bTx, stx)
        print("bTx == $stx\n")
        // Check yo state is stored in the vault.
        b.database.transaction {
            // Simple query.
            val bYo = b.services.vaultService.queryBy<YoState>().states.single().state.data
            assertEquals(bYo.toString(), yo.toString())
            print("$bYo == $yo\n")
            // Using a custom criteria directly referencing schema entity attribute.
            val expression = builder { PersistentYoState::yo.equal("Yo!") }
            val customQuery = VaultCustomQueryCriteria(expression)
            val bYo2 = b.services.vaultService.queryBy<YoState>(customQuery).states.single().state.data
            assertEquals(bYo2.yo, yo.yo)
            print("$bYo2 == $yo\n")
        }
    }
}

class YoContractTests {
    @Before
    fun setup() {
        setCordappPackages("net.corda.yo", "net.corda.testing.contracts")
    }

    @After
    fun tearDown() {
        unsetCordappPackages()
    }

    @Test
    fun yoTransactionMustBeWellFormed() {
        // A pre-made Yo to Bob.
        val yo = YoState(ALICE, BOB)
        // Tests.
        ledger {
            // Input state present.
            transaction {
                input(DUMMY_PROGRAM_ID) { DummyState() }
                command(ALICE_PUBKEY) { YoContract.Send() }
                output(YO_CONTRACT_ID) { yo }
                this.failsWith("There can be no inputs when Yo'ing other parties.")
            }
            // Wrong command.
            transaction {
                output(YO_CONTRACT_ID) { yo }
                command(ALICE_PUBKEY) { DummyCommandData }
                this.failsWith("")
            }
            // Command signed by wrong key.
            transaction {
                output(YO_CONTRACT_ID) { yo }
                command(MINI_CORP_PUBKEY) { YoContract.Send() }
                this.failsWith("The Yo! must be signed by the sender.")
            }
            // Sending to yourself is not allowed.
            transaction {
                output(YO_CONTRACT_ID) { YoState(ALICE, ALICE) }
                command(ALICE_PUBKEY) { YoContract.Send() }
                this.failsWith("No sending Yo's to yourself!")
            }
            transaction {
                output(YO_CONTRACT_ID) { yo }
                command(ALICE_PUBKEY) { YoContract.Send() }
                this.verifies()
            }
        }
    }
}
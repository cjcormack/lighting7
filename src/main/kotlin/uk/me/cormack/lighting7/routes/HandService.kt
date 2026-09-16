package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskBanks
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.warnMalformedBuskPad
import uk.me.cormack.lighting7.state.HandState
import uk.me.cormack.lighting7.state.SelectionSource
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * **What a pick-up reads, decided once.** `hand.pickUp` from a window and a surface's
 * `PickUpPad(padUuid)` binding are two ways of making one gesture, so — exactly as [BuskPressService]
 * is to a press — the resolution lives here rather than in either door.
 *
 * Every lookup is **scoped to one project**, because [HandState] is: a record id crossing a project
 * boundary would put a row from another show in the hand, and the ids a place mutation takes are
 * meaningless outside the project they were read in.
 *
 * The [HandState.Held] this builds carries `holdId`, `pickedUpAtMs` and `expiresAtMs` at their
 * defaults — `HandState.pickUp` stamps all three, and nothing else may.
 */
internal object HandService {

    /**
     * Resolve `{kind, id}` in [projectId] into something the hand can hold, with its summary DTO.
     *
     * Null when the record is not in that project — which covers both "no such row" and "another
     * project's row", deliberately indistinguishably: a window that could tell the two apart could
     * probe another show's id space.
     */
    fun resolve(
        state: State,
        projectId: Int,
        kind: BuskPadKind,
        id: Int,
        pickedUpOn: SelectionSource?,
    ): HandState.Held? = transaction(state.database) {
        resolveInTransaction(state, projectId, pickedUpOn) { kind to id }
    }

    /**
     * The record **on a pad**, for a surface's `PickUpPad` — the pad is the address a binding can
     * carry, and what it picks up is whatever that pad presses.
     *
     * A malformed pad (no record, or more than one) reads as absent, the reading every other pad
     * reader takes.
     */
    fun resolveByPadUuid(
        state: State,
        projectId: Int,
        padUuid: UUID,
        pickedUpOn: SelectionSource?,
    ): HandState.Held? = transaction(state.database) {
        resolveInTransaction(state, projectId, pickedUpOn) {
            val pad = DaoBuskPad.find { DaoBuskPads.uuid eq padUuid }
                .firstOrNull()
                ?.takeIf { it.bank.column.page.project.id.value == projectId }
                ?: return@resolveInTransaction null
            when (val kind = pad.kind) {
                null -> {
                    warnMalformedBuskPad { pad.uuid.toString() }
                    null
                }
                // Safe: `kind` is derived from exactly these three FK columns' `readValues`, which
                // is why `DaoBuskPad.toDto` and `BuskPressService` unwrap them the same way.
                BuskPadKind.TEMPLATE -> kind to pad.template!!.id.value
                BuskPadKind.LOOK -> kind to pad.look!!.id.value
                BuskPadKind.CUE -> kind to pad.cue!!.id.value
            }
        }
    }

    /**
     * Both doors' shared body, in **one** transaction — `BuskPressService.planInTransaction`'s
     * shape, and for its reason: a pick-up that resolved its address in one transaction and its
     * record in another would pay two turns at SQLite's single writer connection and leave a window
     * where the record could go between them.
     *
     * [address] answers the `{kind, id}` to pick up, or null for an address that resolves to
     * nothing. It runs inside the transaction, so it may read entities.
     */
    private fun resolveInTransaction(
        state: State,
        projectId: Int,
        pickedUpOn: SelectionSource?,
        address: () -> Pair<BuskPadKind, Int>?,
    ): HandState.Held? {
        val project = DaoProject.findById(projectId) ?: return null
        val (kind, id) = address() ?: return null
        val records = BuskRecordCache(state)
        return when (kind) {
            BuskPadKind.TEMPLATE -> records.template(project, id)?.let {
                HandState.Held(kind, it.id.value, it.uuid.toString(), template = records.dto(it), pickedUpOn = pickedUpOn)
            }
            BuskPadKind.LOOK -> records.look(project, id)?.let {
                HandState.Held(kind, it.id.value, it.uuid.toString(), look = records.dto(it), pickedUpOn = pickedUpOn)
            }
            BuskPadKind.CUE -> records.cue(project, id)?.let {
                HandState.Held(kind, it.id.value, it.uuid.toString(), cue = records.dto(it), pickedUpOn = pickedUpOn)
            }
        }
    }

    /**
     * Append the held record as a pad on the bank [bankUuid] names, and answer the page it landed
     * on so the caller can broadcast it.
     *
     * The **surface's** half of a place (`HandPlaceInBank`). A window places through its own
     * `POST /busk/banks/{bankId}/pads` and then sends `hand.drop` (D12); a button has no mutation
     * of its own to make, so this runs the same append the route runs — through [appendBuskPad],
     * which is that route's body — rather than a second copy of it. Letting go stays the caller's,
     * so a failed append leaves the item in the hand rather than losing it.
     */
    fun placeInBank(state: State, projectId: Int, bankUuid: UUID, held: HandState.Held): AddPadOutcome =
        transaction(state.database) {
            // Not `NotFound`: that arm is the *bank's*, and the surface logs it as "no busk bank X
            // in project Y". A project that has gone under the press is a different sentence.
            val project = DaoProject.findById(projectId)
                ?: return@transaction AddPadOutcome.Invalid("project $projectId is no longer there")
            val bank = DaoBuskBank.find { DaoBuskBanks.uuid eq bankUuid }
                .firstOrNull()
                ?.takeIf { it.column.page.project.id == project.id }
                ?: return@transaction AddPadOutcome.NotFound
            appendBuskPad(
                state = state,
                project = project,
                bank = bank,
                templateId = held.id.takeIf { held.kind == BuskPadKind.TEMPLATE },
                lookId = held.id.takeIf { held.kind == BuskPadKind.LOOK },
                cueId = held.id.takeIf { held.kind == BuskPadKind.CUE },
                // A button has nowhere to put a page document; it needs the id to broadcast and
                // nothing else, and building the rest would be several queries per press.
                buildPage = false,
            )
        }
}

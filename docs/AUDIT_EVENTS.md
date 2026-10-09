# WIIC audit events

WIIC emits best-effort events through its shaded neutral audit client after the owning operation reaches a final result. Audit failures do not change gameplay. Existing transaction text records retain their separate operational coverage.

The optional per-server audit engine owns SQLite and local staff searches. Each producer writes to its own bounded spool directory even when the engine is absent. Existing gameplay dependencies remain separate from audit transport.

## Event contract

All events use the `mysterria-wiiconomy.` namespace and `STAFF_RESTRICTED` privacy. Every
emitted operation event has a UUID `correlationId` and a separate stable string `businessId`.
Result, failure, refund, courier handoff, and recovery events reuse both values when the
operation reaches an auditable point; preflight rejections may be intentionally sampled or
omitted to protect the main thread and audit sink.
Metadata is bounded and contains only immutable primitives captured at the commit point.

Monetary events use the indexed keys `currency=coppets`, unsigned `amount`, signed `delta`,
`balance_before`, and `balance_after` whenever both balances are observable. Other metadata
uses snake_case. Item projections use `material` and `item_amount`; when present, physical
identity is copied under the canonical indexed top-level keys `item_uuid` and
`parent_item_uuid`. Raw serialized item bytes are never emitted.

A row carries the block position `world`, `x`, `y`, `z` only when its code already holds the
player and passes it in; the bridge adds no position. System rows (null actor, e.g.
evictions from the upkeep task) carry no position.

Sampled preflight rejections: `shop.purchased` rejections are limited to one per player,
material and reason per second; `agora.purchase.failed` with reason `already in progress`
is limited to one per player per 5 seconds; `agora.listing.failed` preflight refusals and
`stash.claimed` click refusals (claim in progress, no free slots) are limited to one per
player and reason per 5 seconds; `plot_shop.purchase_failed` preflight refusals are limited
to one per buyer, shop and reason per second. When a market withdraw is refused,
the reason is `insufficient_funds` only if the balance read before the withdraw was below
the price; otherwise it is `withdraw_failed`. A refused stall withdraw is always reported
as `insufficient funds`, because no balance check separates it from other refusals.

Balance fields are best-effort observations around the external economy call, not an atomic
transaction boundary. The signed `delta` records what WIIC attempted or completed and is the
authoritative monetary projection when concurrent economy activity changes either snapshot.

`plots.rent.charge_pending` is an `ATTEMPTED` observation of a debit that already succeeded
in Vault but is still waiting for the plot-row commit. Consumers should use the final
committed, failed, or refunded outcome for settlement and retain the pending row for crash
reconciliation rather than counting the same debit twice.

Item projections taken on the main thread hold only `material` and `item_amount`, read from
the stack's own type and amount or from the record the code already holds; item meta and PDC
are not read. Physical identity comes from serialized item bytes, parsed by a
Bukkit-independent NBT reader only off the main thread: in the async payment task of a
listing, a market purchase and a stall purchase, and on the DB executor for expiry and
journal recovery. No audit path deserializes an `ItemStack` off the main thread.

`*.payment_indeterminate` (FAILED, HIGH) is emitted when the economy provider throws.
Balance observations cannot prove whether that operation moved money because other payments
can change the same account concurrently. WIIC then neither compensates
(no item return, no refund) nor finalizes (no goods); metadata carries `attempted_amount`,
both balance observations and `payment=INDETERMINATE` for manual reconciliation. Refund rows
carry `payment` (`SUCCESS`/`FAILED`/`INDETERMINATE`).

`wallet.withdrawn` is COMMITTED only after the coin was handed to the player on the main
thread; a debit whose handoff failed (player offline) is FAILED with reason
`debited; coin handoff failed: player offline`; its `delta` still records the debit.

## Coverage

| Event | Commit/failure point | Main metadata |
| --- | --- | --- |
| `wallet.deposited`, `wallet.withdrawn`, `wallet.sold` | Vault wallet operation result (withdrawn: after coin handoff) | monetary projection, item projection, attempted_amount, success |
| `*.payment_indeterminate` (FAILED, HIGH) | Provider exception with unprovable outcome (wallet, shop, agora purchase/listing fee, plot rent/upkeep, stall purchase, courier fee, ledger claim) | attempted_amount, balances, payment, operation context |
| `shop.purchased`, `shop.refunded` | Admin shop charge/delivery/refund | monetary projection, material, item_amount, attempted_total when declined, outcome reason |
| `agora.listing.created`, `agora.listing.cancelled` | Listing DB commit | listing_id, price, fee, item projection, plot_id |
| `agora.listing.failed`, `agora.listing.fee_refunded` | Listing validation/charge/insert failure; cancel DB error (operation=cancel) | price, fee, listing/request id, reason |
| `agora.purchase.completed`, `agora.purchase.failed`, `agora.purchase.refunded` | Market reservation, sale commit, and refund | monetary projection, listing_id, tax, net, item projection |
| `agora.purchase.recovered`, `agora.purchase.recovery_refunded`, `agora.purchase.recovery_unproven` | Journal recovery result | original purchase identity, monetary projection, listing_id |
| `agora.listing.item_recovered` | Interrupted listing item restored to stash | listing_id, item projection |
| `courier.contract.created`, `courier.contract.withdrawn` | Courier horn escrow mutation | courier_type, item projection |
| `courier.contract.failed`, `courier.contract.withdraw_failed` | Courier contract errors, duplicate contract, no contract to withdraw, corrupt escrowed horn | reason, courier_type, item projection |
| `courier.delivery.dispatched`, `courier.delivery.failed` | Stash claim and postman handoff | purchase identity, stash_id, seller, item projection, fee, courier_type |
| `courier.fee.collected`, `courier.fee.failed` | Optional delivery fee result | purchase identity, monetary projection, fee |
| `courier.fee.denied` (DENIED) | Balance below the delivery fee; goods stay in the stash | purchase identity, fee, balance, stash_id |
| `stash.deposited`, `stash.deposit_failed` | Stash row insertion | stash id, source, reference, item projection |
| `stash.claimed` | Stash claim batch result | delivered, remaining, claimed_ids, restashed_ids (claimed rows handed back to the stash) |
| `ledger.claimed`, `ledger.claim_pending_recovery`, `ledger.claim_failed`, `ledger.claim_recovered`, `ledger.claim_recovery_unproven` | Proceeds deposit/claim/recovery result; claim_failed also on journal initialization failure | original claim identity, monetary projection; batch_id, claim_sum, reverted on journal failure |
| `ledger.claim_marker_failed` (FAILED, HIGH) | Deposit landed but the CLAIM_DEPOSITED journal marker could not be written | monetary projection, deposit_landed=true, batch_id, intent_removed |
| `plots.rent.charge_pending`, `plots.rent.committed`, `plots.rent.failed` | Plot rent charge and DB claim | monetary projection, plot_id, paid_until, reason |
| `plots.rent.charge_pending`, `plots.rent.upkeep`, `plots.rent.upkeep_failed` | Plot extension charge and DB update | monetary projection, plot_id, paid_until |
| `plots.rent.refunded` | Failed rent/upkeep refund | original rent/upkeep identity, monetary projection, plot_id, operation, reason |
| `plots.eviction.db_committed`, `plots.eviction.failed` | Eviction DB commit (rental cleared, stash rows stored); actor null (system) for upkeep and voluntary hand-back, the staff member for `/wiicmarket plot evict` (HIGH risk, with `actor_name`, optional `admin_reason`); subject = renter. A staff eviction refused because the region or world is missing also emits `plots.eviction.failed` (stage=refused) | plot_id, harvested_stacks, material totals, up to 6 item summaries with material and amount (`harvested_item_summaries_truncated` when more), reason, stage=db_commit, restoration=pending/not_applicable |
| `plots.eviction.restored`, `plots.eviction.restore_failed` | World snapshot replay after the DB commit; same actor as the eviction | plot_id, stage=world_restoration, eviction_reason, failure reason |
| `plot_shop.created`, `plot_shop.create_failed` | Stall counter DB insert | plot_id, price, bundle |
| `plot_shop.updated`, `plot_shop.update_failed` | Stall goods/price mutation | plot_id, price, stocked, item projection |
| `plot_shop.purchase_completed`, `plot_shop.purchase_failed` | Stall charge, stock, and ledger result | purchase identity, monetary projection, plot_id, quantity, tax, net, item projection |
| `plot_shop.ledger_failed`, `plot_shop.refunded` | Stall ledger failure/refund | purchase identity, monetary projection, plot_id, shop_id, net, reason |
| `agora.listing.expired`, `agora.listing.expire_failed` | Expiry sweeper moved an overdue listing to the seller's stash (after the DB commit) or the transaction failed; actor null (system), subject = seller | listing_id, price, item projection, plot_id, destination=stash, stash_source |
| `agora.reservations.released` | Expiry sweeper released stale PENDING_PAYMENT reservations; one batch row per sweep that released any, actor null | released (count), timeout_ms, journal_protected |
| `entrance.removed` | Entrance registry removal after the DB delete finished (COMMITTED), or FAILED when the delete failed (the registry entry is already gone). Causes: staff removal, a player breaking the door, an explosion, the door block gone, the land gone or moved. Actor is the staff member or the player who broke the door; null for the sweeper and explosions. Subject = the entrance owner | entrance_id, land_id, hub, created_by, door_world/door_x/door_y/door_z, removal_cause, admin_reason |

## Admin command rows

Every `/wiicmarket` and `/wiic` invocation emits one `admin.command.observed` row (OBSERVED,
LOW) with the command label, the full argument text (capped at 512 characters) and
`arg_count`, including read-only subcommands and attempts that are then refused. Both
commands are plain executors, not a shared framework, so the row is emitted at the top of
each `onCommand`. State-changing subcommands add their own row, always with the real actor.

Actor fields on every admin row: the actor id is the player's uuid, or null for console and
RCON senders. `actor_name` is the sender name ("CONSOLE" for the console) and `sender_type`
is `player`, `console`, `rcon` or `other`. A player actor also carries the block position
`world`/`x`/`y`/`z` captured from the command sender, so no lookup happens for the row.
Rows are emitted directly from the thread that finishes the work (the command thread for
in-memory changes, the callback thread for saves), never through a scheduler hop.

| Event | Command | Outcome rule | Main metadata |
| --- | --- | --- | --- |
| `admin.command.observed` | any `/wiicmarket` or `/wiic` | OBSERVED | command, args, arg_count |
| `admin.entrance_item.given` (HIGH) | `/wiicmarket give-entrance` | COMMITTED after handover; subject = recipient | material, item_amount |
| `admin.npc.created` | `/wiicmarket npc create <role>` | COMMITTED after spawn, FAILED if the spawn throws | role, npc_id, plot_id |
| `admin.npc.plot_vendor_created` | `/wiicmarket npc create plot_vendor <plot>` | COMMITTED after spawn; subject = the plot's renter, if rented | plot_id, npc_id, renter_name |
| `admin.npc.plot_vendor_bound` | same command | COMMITTED or FAILED when the DB write for the binding finishes; same identity as the row above | plot_id, npc_id |
| `admin.npc.removed` | `/wiicmarket npc remove` | COMMITTED when an NPC was in range (no row otherwise) | npc_id |
| `admin.entrance.hub_registered` | `/wiicmarket entrance hub-here` | COMMITTED or FAILED when the DB insert finishes | entrance_id, door_world, door_x/y/z |
| `admin.entrance.exit_door_set` (LOW) | `/wiicmarket entrance exit-here` | COMMITTED, or FAILED when market.yml could not be saved | old_exit_door, new_exit_door |
| `entrance.removed` | `/wiicmarket entrance remove [reason]` | see Coverage | admin_reason |
| `admin.plot.defined` | `/wiicmarket plot define <id>` | COMMITTED when saved and parsed back, else FAILED | plot_id, volume, old_region, new_region |
| `admin.plot.snapshot` | `plot define` and `plot snapshot <id>` | COMMITTED or FAILED when the snapshot DB write finishes | plot_id, trigger, volume |
| `admin.plot.vendorspot_set` (LOW) | `/wiicmarket plot vendorspot <id>` | COMMITTED, or FAILED when market.yml could not be saved | plot_id, old_spot, new_spot |
| `admin.plot_wand.given` (LOW) | `/wiicmarket plot wand` | COMMITTED after handover | none |
| `plots.eviction.*` | `/wiicmarket plot evict <id> [reason]` | see Coverage | admin_reason |
| `admin.bounds.set`, `admin.bounds.cleared` | `/wiicmarket bounds set` / `clear` | COMMITTED, or FAILED when market.yml could not be saved | old_bounds, new_bounds |
| `admin.market.reloaded` (LOW) | `/wiicmarket reload` | COMMITTED after the reload, FAILED if it throws | none |
| `admin.wiic.reloaded` (LOW) | `/wiic reload` | COMMITTED after the reload, FAILED if it throws; replaces the console-only line as the durable record | none |
| `admin.villager_trades.restored` | `/wiic restore` | COMMITTED after the sweep | restored, villagers_seen |
| `admin.debug.toggled` (OBSERVED, LOW) | `/wiic debug` | OBSERVED, because `saveConfig` reports no result | flag, old_value, new_value |

`old_*` values are read from in-memory state before the change. The optional trailing
`[reason]` on `plot evict` and `entrance remove` is free text (200 characters at most) and
is stored as `admin_reason`; without it the commands behave as before. `plot snapshot`
cannot record the snapshot it replaces: the stored blob is not read back for the row.
`agora.reservations.released` is a count only, because the release is a single UPDATE that
returns no listing ids.

A ledger CLAIM without a matching CLAIM_DEPOSITED proof stays CLAIMING during recovery.
Further claims by that owner are blocked until staff reconcile the uncertain payout.
This also applies if the interrupted deposit never reached the economy provider.

An Agora purchase uses its journal attempt ID as the business identity, while `listing_id`
remains the purchased resource. WIIC courier events retain that purchase identity and expose
the stash row separately as `stash_id`, allowing downstream Delivery emitters to reuse the
same business ID without conflating a purchase, listing, and stash record.

## History ownership

`logging.legacy-text-history` defaults to true: existing staff tooling still reads the
per-player `logs/<player>.log` files, so the export stays on. Retention: a file rotates at
8 MiB into numbered backups `<player>.log.1` (newest) to `<player>.log.5` (oldest); each
rotation shifts the backups up and only discards the generation beyond `.5`, so at least
five full backups plus the live file are always kept. Planned change: once staff
tooling reads the shared audit, the default flips to false (files already on disk are kept).
The SQL transactions trail remains part of the atomic sale transaction; the shared audit feed
cannot replace its durability guarantee. Listings, proceeds, stash and the fsynced market
recovery journal remain authoritative state. Actionable failure diagnostics remain available
independently of either history export.

## Balance mutation paths

Every balance change goes through `VaultUtil.deposit`/`withdraw`, and each caller emits an
audit row. `WalletListener`, `WalletGUI`, `GuiUtil` and `MarketIndex` call `WIIC.getEcon()`
only to read balances for display and the market index, so they emit nothing.

## Main thread

Rows add no main-thread reads made only for the row. Dropped for that reason: the actor
position the bridge used to look up for every row emitted on the main thread; `item_uuid`
and `parent_item_uuid` on rows whose item is only available on the main thread (stash,
courier contract and delivery, wallet, listing refusals, stall configuration, market
purchase failures before the payment, eviction item summaries); and `role` and `plot_id` on
`admin.npc.removed`.

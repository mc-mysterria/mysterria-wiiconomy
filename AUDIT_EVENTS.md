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

Rows whose actor is an online player carry the actor's block position under `world`, `x`,
`y`, `z` (added centrally by the bridge unless the row already has a position). System rows
(null actor, e.g. evictions from the upkeep task) carry no position.

Sampled preflight rejections: `shop.purchased` rejections are limited to one per player,
material and reason per second; `agora.purchase.failed` with reason `already in progress`
is limited to one per player per 5 seconds; `agora.listing.failed` preflight refusals and
`stash.claimed` click refusals (claim in progress, no free slots) are limited to one per
player and reason per 5 seconds. When a market withdraw is refused,
the reason is `insufficient_funds` only if the balance read before the withdraw was below
the price; otherwise it is `withdraw_failed`.

Balance fields are best-effort observations around the external economy call, not an atomic
transaction boundary. The signed `delta` records what WIIC attempted or completed and is the
authoritative monetary projection when concurrent economy activity changes either snapshot.

`plots.rent.charge_pending` is an `ATTEMPTED` observation of a debit that already succeeded
in Vault but is still waiting for the plot-row commit. Consumers should use the final
committed, failed, or refunded outcome for settlement and retain the pending row for crash
reconciliation rather than counting the same debit twice.

Item projections are built on the main thread (from the live `ItemStack`) before any async
hop, or from serialized item bytes by a Bukkit-independent NBT reader that is safe on any
thread. No audit path deserializes an `ItemStack` off the main thread.

`*.payment_indeterminate` (FAILED, HIGH) is emitted when the economy provider threw and a
balance re-read could not prove whether the money moved. WIIC then neither compensates
(no item return, no refund) nor finalizes (no goods); metadata carries `attempted_amount`,
both balance observations and `payment=INDETERMINATE` for manual reconciliation. Refund rows
carry `payment` (`SUCCESS`/`FAILED`/`INDETERMINATE`).

`wallet.withdrawn` is COMMITTED only after the coin was handed to the player on the main
thread; a debit whose handoff failed (player offline) is FAILED with reason
`debited; coin handoff failed: player offline` and a non-zero balance change.

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
| `ledger.claimed`, `ledger.claim_pending_recovery`, `ledger.claim_failed`, `ledger.claim_recovered`, `ledger.claim_reverted` | Proceeds deposit/claim/recovery result; claim_failed also on journal initialization failure | original claim identity, monetary projection; batch_id, claim_sum, reverted on journal failure |
| `ledger.claim_marker_failed` (FAILED, HIGH) | Deposit landed but the CLAIM_DEPOSITED journal marker could not be written | monetary projection, deposit_landed=true, batch_id, intent_removed |
| `plots.rent.charge_pending`, `plots.rent.committed`, `plots.rent.failed` | Plot rent charge and DB claim | monetary projection, plot_id, paid_until, reason |
| `plots.rent.charge_pending`, `plots.rent.upkeep`, `plots.rent.upkeep_failed` | Plot extension charge and DB update | monetary projection, plot_id, paid_until |
| `plots.rent.refunded` | Failed rent/upkeep refund | original rent/upkeep identity, monetary projection, plot_id, operation, reason |
| `plots.eviction.db_committed`, `plots.eviction.failed` | Eviction DB commit (rental cleared, stash rows stored); actor null (system), subject = renter | plot_id, harvested_stacks, material totals, bounded item summaries, reason, stage=db_commit, restoration=pending/not_applicable |
| `plots.eviction.restored`, `plots.eviction.restore_failed` | World snapshot replay after the DB commit | plot_id, stage=world_restoration, eviction_reason, failure reason |
| `plot_shop.created`, `plot_shop.create_failed` | Stall counter DB insert | plot_id, price, bundle |
| `plot_shop.updated`, `plot_shop.update_failed` | Stall goods/price mutation | plot_id, price, stocked, item projection |
| `plot_shop.purchase_completed`, `plot_shop.purchase_failed` | Stall charge, stock, and ledger result | purchase identity, monetary projection, plot_id, quantity, tax, net, item projection |
| `plot_shop.ledger_failed`, `plot_shop.refunded` | Stall ledger failure/refund | purchase identity, monetary projection, plot_id, shop_id, net, reason |

An Agora purchase uses its journal attempt ID as the business identity, while `listing_id`
remains the purchased resource. WIIC courier events retain that purchase identity and expose
the stash row separately as `stash_id`, allowing downstream Delivery emitters to reuse the
same business ID without conflating a purchase, listing, and stash record.
# History ownership

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

# Balance mutation paths

Every balance change goes through `VaultUtil.deposit`/`withdraw`, and each caller emits an
audit row. `WalletListener`, `WalletGUI`, `GuiUtil` and `MarketIndex` call `WIIC.getEcon()`
only to read balances for display and the market index, so they emit nothing.

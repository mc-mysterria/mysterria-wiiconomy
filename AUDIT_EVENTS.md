# WIIC audit events

Rows go through the shaded audit client to the spool under `plugins/mysterria-audit-spool`
(producer `mysterria-wiiconomy`, privacy `STAFF_RESTRICTED`); the optional audit engine ingests them.
Emission is best effort and never changes gameplay. Every row has a UUID `correlationId` and a string
`businessId` (`wiic:<domain>:<id>`) shared by all rows of one operation.

Common metadata: money rows carry `currency=coppets`, `amount`, signed `delta`, and `balance_before` /
`balance_after` when observable. Item rows carry `material`, `item_amount`, and `item_uuid` /
`parent_item_uuid` when present. Rows with an online player actor carry `world`, `x`, `y`, `z`;
system rows (null actor) carry no position.

Outcome is COMMITTED on success and FAILED otherwise unless noted. All names are prefixed `mysterria-wiiconomy.`.

## Wallet and admin shop

- `wallet.deposited` / `wallet.withdrawn` / `wallet.sold`: vault GUI coin and item operations; `attempted_amount`, `success`, item. `withdrawn` commits only after the coin is handed over.
- `shop.purchased`: admin shop purchase; `material`, `item_amount`, `attempted_total` on insufficient funds. Rejections sampled to one per player, material and reason per second.
- `shop.refunded`: refund after aborted delivery; `payment`.
- `*.payment_indeterminate` (FAILED, risk HIGH): provider threw and the outcome is unproven; no refund or handoff happened. `attempted_amount`, both balances, `payment=INDETERMINATE`. Emitted for wallet, shop, agora purchase and listing fee, plot rent and upkeep, stall purchase, courier fee, ledger claim.

## Agora market

- `agora.listing.created`: listing committed; `price`, `fee`, `plot_id`, item.
- `agora.listing.cancelled`: listing moved to stash.
- `agora.listing.failed`: validation, fee, insert or cancel failure; `price`, `fee`, `listing_request_id`. Preflight refusals sampled to one per seller and reason per 5 s.
- `agora.listing.fee_refunded`: fee refund; `fee`, `listing_id`, `payment`.
- `agora.listing.item_recovered`: journal recovery returned an unlisted item to stash; `listing_id`, item.
- `agora.purchase.completed`: sale committed; `listing_id`, `tax`, `net`, item.
- `agora.purchase.failed`: reservation, journal, withdraw or commit failure; reason `insufficient_funds` only when the observed balance was below the price, else `withdraw_failed`. `already in progress` sampled to one per player per 5 s.
- `agora.purchase.refunded`: buyer refund; `listing_id`, `payment`.
- `agora.purchase.recovered` / `agora.purchase.recovery_refunded` / `agora.purchase.recovery_unproven` (FAILED): journal recovery result; `listing_id`.

## Ledger, stash and courier

- `ledger.claimed`: proceeds deposited and claim finalized.
- `ledger.claim_failed`: claim init, journal or deposit failure, or an earlier claim still unresolved (`earlier claim unresolved`); `batch_id`, `claim_sum`, `reverted` on journal failure.
- `ledger.claim_pending_recovery` (FAILED): deposit landed, finalize failed; recovery completes it.
- `ledger.claim_marker_failed` (FAILED, risk HIGH): deposit landed but the journal marker write failed; `deposit_landed`, `batch_id`, `intent_removed`.
- `ledger.claim_recovered`: journal recovery completed a proven claim; `claim_id`.
- `ledger.claim_recovery_unproven` (FAILED): claim intent without deposit proof; rows stay CLAIMING and the owner's further claims are blocked until staff reconcile. `claim_id`, `claim_sum`.
- `stash.deposited` / `stash.deposit_failed`: stash row insert; `source`, `reference`, item.
- `stash.claimed`: COMMITTED when at least one item was delivered; `delivered`, `remaining`, `claimed_ids`, `restashed_ids`. Click refusals sampled to one per player and reason per 5 s.
- `courier.contract.created` / `courier.contract.failed`: horn escrow; `courier_type`, item.
- `courier.contract.withdrawn` / `courier.contract.withdraw_failed`: horn returned, missing contract or corrupt horn; `courier_type`, `contract_deleted`.
- `courier.delivery.dispatched` / `courier.delivery.failed`: postman handoff of a purchase; purchase identity, `stash_id`, `fee`, `courier_type`, item.
- `courier.fee.collected` / `courier.fee.failed`: delivery fee charge; `fee`. `failed` with reason `balance_unavailable` when the balance could not be read.
- `courier.fee.denied` (DENIED): observed balance below the fee; goods stay in stash. `fee`, `balance`, `stash_id`.

## Plots and stalls

- `plots.rent.charge_pending` (ATTEMPTED): rent or upkeep debited, DB update pending; `plot_id`, `operation`. Settle on the final row.
- `plots.rent.committed` / `plots.rent.failed`: plot rent; `plot_id`, `paid_until`.
- `plots.rent.upkeep` / `plots.rent.upkeep_failed`: plot extension; `plot_id`, `paid_until`.
- `plots.rent.refunded`: rent or upkeep refund; `plot_id`, `operation`, `payment`.
- `plots.eviction.db_committed` / `plots.eviction.failed`: eviction DB stage, null actor, subject is the renter; `plot_id`, `harvested_stacks`, `harvested_items`, bounded `harvested_item_summaries`, `restoration`.
- `plots.eviction.restored` / `plots.eviction.restore_failed`: world snapshot replay; `plot_id`, `eviction_reason`.
- `plot_shop.created` / `plot_shop.create_failed`: stall insert; `plot_id`, `price`, `bundle`.
- `plot_shop.updated` / `plot_shop.update_failed`: stall goods or price change; `plot_id`, `price`, `stocked`, item.
- `plot_shop.purchase_completed` / `plot_shop.purchase_failed`: stall purchase; `plot_id`, `quantity`, `tax`, `net`, item. Rejections sampled to one per buyer, stall and reason per second.
- `plot_shop.ledger_failed`: owner ledger write failed after the sale; `net`, `quantity`.
- `plot_shop.refunded`: stall refund; `plot_id`, `shop_id`, `payment`.

`logging.legacy-text-history` (default true) keeps the per-player `logs/<player>.log` export, rotated at 8 MiB with five backups.

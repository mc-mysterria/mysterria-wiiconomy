# 🪙 WIIC Economy Plugin - Developer Context

## Project Overview
WIIC (WIIConomy) is a sophisticated Minecraft economy plugin designed for 1.21+ Paper/Spigot servers. It implements a multi-tier physical currency system (Coppet, Lick, VerlDor) while maintaining compatibility with the standard `Vault` economy abstraction.

### Main Technologies
- **Platform:** PaperMC (1.21+)
- **Language:** Java 25 (Source/Target compatibility)
- **Build Tool:** Gradle
- **Core APIs:** Paper-API, Kyori Adventure, Vault API
- **Libraries:**
  - `InventoryFramework` (stefvanschie): GUI management
  - `Lombok`: Boilerplate reduction
  - `Gson`: JSON serialization for wallet data

### Project Architecture
- **Entry Point:** `dev.ua.ikeepcalm.wiic.WIIC`
- **Currency Logic:** `dev.ua.ikeepcalm.wiic.utils.CoinUtil` handles the tiered logic (64:1 conversion).
- **GUIs:** Found in `dev.ua.ikeepcalm.wiic.gui`, using `InventoryFramework`.
- **Services:** `WalletManager`, `PriceAppraiser`, `SoldItemsManager` handle business logic.
- **Data Storage:** JSON-based wallet data stored in the `plugins/WIIC/wallets/` directory.

---

## Building and Running
The project uses Gradle for dependency management and building.

- **Build Fat JAR:**
  ```powershell
  ./gradlew shadowJar
  ```
- **Full Build (Includes Shadow):**
  ```powershell
  ./gradlew build
  ```
- **Run Local Test Server:**
  ```powershell
  ./gradlew runServer
  ```
  *(Configured for Paper 1.21.8/26.1.1 in build.gradle)*

---

## Development Conventions
- **Language Level:** Uses Java 25. Utilize modern features where appropriate.
- **Message System:** All messages should use `Kyori Adventure` components.
- **GUIs:** Do not use raw Bukkit inventories. Use `InventoryFramework` implementations.
- **Item Meta:** Use `ItemUtil` for modifying item NBT and display properties to ensure consistency.
---

## Core Systems
- **Tiered Currency:**
  - `Coppet`: Base unit.
  - `Lick`: 64 Coppets.
  - `VerlDor`: 64 Licks.
- **Shatter System:** Allows players to break higher-tier coins into lower-tier ones via `/shatter`.
- **Villager Integration:** Custom listeners to inject coin-based trades into villager interfaces.
- **Vault Integration:** Syncs the physical physical currency with virtual balances.

---

## Command Overview
- `/wallet`: Opens the personal wallet GUI.
- `/shatter`: Opens the shattering interface for currency conversion.
- `/wiic <reload|restore|debug|version>`: Admin management commands.

---

## Configuration
- `config.yml`: Main plugin settings.
- `sold-items.yml`: Persistence for the dynamic price/appraisal system.

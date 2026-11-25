# 🪙 WIIC Economy Plugin

<div align="center">

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20+-brightgreen.svg)](https://minecraft.net)
[![Spigot](https://img.shields.io/badge/Spigot-Compatible-orange.svg)](https://spigotmc.org)
[![Paper](https://img.shields.io/badge/Paper-Optimized-blue.svg)](https://papermc.io)

*A comprehensive economy plugin with custom currency system for Minecraft servers*

---

### 🎯 **Features**

</div>

## 💰 **Custom Currency System**
- **🥉 Coppet** - Base currency for everyday trades
- **🥈 Lick** - Mid-tier currency (64 Coppets = 1 Lick)  
- **🥇 VerlDor** - Premium currency (64 Licks = 1 VerlDor)

## 🏪 **Advanced Trading**
- **Smart Villager Integration** - Automatic emerald → coin conversion
- **Discount Support** - Hero of the Village & zombification discounts
- **Dynamic Pricing** - Demand-based market fluctuations
- **Item Appraisal** - Intelligent item valuation system

## 🎒 **Wallet Management**
- **Visual Wallet GUI** - Intuitive currency management
- **Binding System** - Secure item → currency conversion
- **Daily Limits** - Configurable sell restrictions

## ⚙️ **Administration**
- **Hot Reload** - Runtime configuration updates
- **Vault Integration** - Compatible with economy plugins
- **Multi-language** - English and Ukrainian locale support

---

<div align="center">

### 📋 **Commands**

| Command | Description | Permission |
|---------|-------------|------------|
| `/wallet` | Open wallet GUI | `wiic.wallet` |
| `/shatter` | Convert items to coins | `wiic.shatter` |

</div>

## 🔌 **Hooks for other plugins**

WIIC exposes two small, read-only hooks. Both are plain Bukkit APIs, so other plugins do not need a compile dependency on WIIC.

- **Event** `dev.ua.ikeepcalm.wiic.api.event.MarketTradeEvent` fires on the main thread after a paid market trade completes, both when a player buys another player's listing and when a player buys from the curio dealer. Getters: `getBuyerUuid()`, `getSellerUuid()` (null when WIIC was the seller), `getMaterial()`, `getItemBytes()` (WIIC's serialized `ItemStack`), `getAmount()`, `getTotalPrice()` and `getCurrency()` (always `coppets`). The event is read-only and cannot be cancelled; failed, refunded or merely reserved purchases do not fire it.
- **Service** `dev.ua.ikeepcalm.wiic.api.MarketPrices`, registered with Bukkit's `ServicesManager`: `OptionalDouble price(Material material)` returns the live `/shop` unit price in coppets for a material WIIC sells, or an empty `OptionalDouble` when WIIC has no price for that material. The price includes the current market index, so read it when needed instead of caching it.

```java
RegisteredServiceProvider<MarketPrices> provider = Bukkit.getServicesManager().getRegistration(MarketPrices.class);
OptionalDouble price = provider == null ? OptionalDouble.empty() : provider.getProvider().price(Material.STONE);
```

### 🔧 **Installation**

1. Download the latest release
2. Place `WIIC.jar` in your `plugins/` folder
3. Restart your server
4. Configure `config.yml` to your needs

### 📊 **Requirements**

- **Minecraft**: 1.21+
- **Server**: Spigot/Paper
- **Dependencies**: Vault, iConomy

---

<div align="center">

*Made with ❤️ by [ikeepcalm](https://github.com/ikeepcalm)*

</div>

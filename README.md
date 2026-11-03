# CaterEconomy

MiaoMC 自用的多货币经济插件。一个 jar 支持任意数量的自定义货币，每种货币可以单独选择存在哪里：比如同一个子服里，喵币和星币存在各子服共享的 MySQL，银币只存在本服的本地文件。

点券不归本插件管，仍由 PlayerPoints 负责。

## 目录

- [兼容版本](#兼容版本)
- [功能一览](#功能一览)
- [安装](#安装)
- [配置说明](#配置说明)
- [转账税](#转账税)
- [每日接收上限](#每日接收上限)
- [同 IP 防小号](#同-ip-防小号)
- [命令](#命令)
- [权限](#权限)
- [变量](#变量)
- [兼容旧命令和旧变量](#兼容旧命令和旧变量)
- [从 GemsEconomy 迁移](#从-gemseconomy-迁移)
- [数据表](#数据表)
- [常见问题](#常见问题)
- [构建](#构建)

## 兼容版本

| 服务端 | Java | 状态 |
|---|---|---|
| Paper 1.8.8 | 8 | 已实测 |
| Beast / Paper 1.12.2 | 17 | 已实测 |
| Paper 26.3 | 25 | 已实测 |

- 编译成 Java 8 字节码，只用 Bukkit 公开 API，不碰服务端内部代码（NMS）。所以 1.8 到 26.x 之间的版本理论上都能用。
- 本地存储用的是服务端自带的 SQLite 驱动，SQL 写法照顾到了 1.8.8 自带的老版本驱动。
- 不含自动更新、数据上报和远程代码。

## 功能一览

- **自定义货币**：想加几种就加几种，每种可以单独设置名称、别名、小数位（0~4）、初始余额、余额上限和显示格式。
- **每种货币单独选存储**：`mysql` 由各子服共享，`sqlite` 只属于本服。某个存储连不上时，只停用用它的货币，其他货币照常可用。
- **不会算错钱**：加钱、扣钱都在数据库里用"满足条件才更新"的方式一次完成。多个子服同时改同一个玩家，也不会多扣或扣成负数。金额按整数存储，没有小数误差。
- **玩家转账**：每种货币可以单独设置能不能转、单笔上下限、冷却时间、大额二次确认。
- **转账税**：按百分比收税，可以选择由付款人额外付，或者从到账金额里扣。
- **每日接收上限**：每个玩家每天最多收多少转账，零点自动重置。
- **同 IP 防小号**：用过同一个 IP 的账号之间，限制每天的转账次数和总额，也可以直接禁止。
- **流水记录**：每一笔变动都会记下时间、子服、操作人和变动后的余额。玩家可以查自己的转账记录，管理员能查任何人的全部记录。
- **兼容旧的 `/eco` 命令**：Essentials 和 GemsEconomy 的写法都能直接用。
- **Vault**：把一种指定的货币提供给 Essentials、商店等插件。
- **PlaceholderAPI**：提供 `%catereconomy_...%` 和 `%eco_...%` 两套相同的变量，读取时只看缓存，不会卡服。
- **从 GemsEconomy 导入**：只读取对方数据库，可以先预览，确认后再写入。

## 安装

1. 把 `CaterEconomy-1.0.0.jar` 放进要用货币的子服的 `plugins` 目录。
2. 建议先装好 Vault 和 PlaceholderAPI（不装也能运行，只是没有对应功能）。
3. 启动一次服务器，插件会生成 `plugins/CaterEconomy/config.yml` 和 `messages.yml`。
4. 修改 `config.yml`：
   - `server-name` 改成本服名字。
   - `storages` 里填好 MySQL 的地址、库名、账号和密码。
   - 按需要调整 `currencies`。
5. 执行 `/catereconomy reload`，再用 `/catereconomy info` 确认每个存储都显示"正常"。
6. 卸掉 GemsEconomy，并关闭 Essentials 自带的经济命令，否则 `/eco`、`/pay`、`/money` 和 Vault 会冲突。

**多个子服共享余额**：各子服的同一个存储填相同的 MySQL 库和相同的 `table-prefix` 就行。只在本服用的货币，用 `sqlite` 存储。

**同 IP 限制需要真实 IP**：子服在 BungeeCord 后面时，必须在子服的 `spigot.yml` 里设置 `bungeecord: true`，否则所有玩家的 IP 都是 127.0.0.1，同 IP 限制不会生效。插件发现这种情况时会在后台提醒。

## 配置说明

`config.yml` 的每一行都写了中文注释，这里只说明结构：

```yaml
server-name: "lobby"          # 本服名称
storages:                     # 存储后端，名字随意
  shared: { type: mysql, ... }
  local:  { type: sqlite, file: "local.db" }
currencies:                   # 货币，键名就是货币 id
  miaobi:
    name: "喵币"
    storage: shared           # 用哪个存储
    pay:                      # 转账设置（税、每日上限、同 IP）
      ...
default-currency: miaobi      # 命令里不写货币时用哪种
vault: { currency: miaobi }   # 提供给 Vault 的货币
```

**新增一种货币**：在 `currencies` 下复制一段，改掉 id（只能用小写字母、数字和下划线）和其他内容，然后执行 `/catereconomy reload`。

**注意**：
- 货币有数据之后，不要再改它的 id。
- 不要把 `decimals` 改小。比如从 2 改成 0，原来存的 12.34 就会显示成 1234。

## 转账税

```yaml
pay:
  tax:
    percent: 5        # 税率 5%
    mode: sender      # 或 receiver
```

| mode | 玩家输入 `/pay 小明 100` | 付款人扣除 | 小明到账 | 税 |
|---|---|---|---|---|
| `sender` | 付款人额外付税 | 105 | 100 | 5 |
| `receiver` | 从到账里扣 | 100 | 95 | 5 |

- 税额向上取整到货币的最小单位，比如整数货币的 1% 税，转 1 也要收 1。
- 税款直接销毁，不归任何人。
- 拥有 `catereconomy.bypass.tax` 权限的玩家转账免税。
- 扣税后对方到账为 0 时，会拒绝这笔转账。
- 流水里会记下每笔的税额。玩家可以用 `/pay limit` 查看当前税率。

## 每日接收上限

```yaml
pay:
  daily-receive-limit: 100000   # 0 为不限
```

- 按**到账金额**统计，所有人转给同一个玩家的金额加在一起算。
- 按 `daily-reset.timezone` 设定的时区，每天零点重置。计数是按日期分开存的，过了零点自然从 0 开始，不需要定时任务去清零。
- 超过上限时，整笔转账会被拒绝，付款人不扣钱，并提示对方今天还能收多少。
- 收款人可以用 `/pay limit [货币]` 查看今天已收多少、还剩多少。
- 检查和计数在同一个数据库事务里完成，多个子服同时往一个人身上转钱也不会超出上限。

## 同 IP 防小号

```yaml
same-ip:                 # 全局设置
  window-days: 30        # 最近 30 天内用过同一个 IP 就算同 IP 账号
  ignore-ips: [127.0.0.1, "0:0:0:0:0:0:0:1"]
currencies:
  miaobi:
    pay:
      same-ip:
        enabled: true
        max-count: 3       # 每天最多 3 次；0 为完全禁止，-1 为不限
        max-amount: 5000   # 每天最多 5000（按到账金额）；-1 为不限
```

工作方式：
1. 玩家每次进服，插件都会记下他的 IP（存在该货币所在的存储里，MySQL 存储由各子服共享）。
2. 转账时，如果付款人和收款人在最近 `window-days` 天内用过同一个 IP，就算同 IP 账号。
3. 同 IP 账号之间的转账，按**付款人当前 IP** 统计当天的次数和金额。同一个 IP 下的所有账号共用这份额度，开再多小号也绕不过去。
4. `max-count: 0` 表示同 IP 账号之间完全不能转这种货币。
5. 拥有 `catereconomy.bypass.sameip` 权限的玩家不受限制，可以给确实共用网络的家人或室友使用。
6. 超过 `window-days` 的 IP 记录每小时清理一次。

不同货币可以设置不同的规则，比如喵币允许同 IP 每天转 3 次，星币完全禁止。

## 命令

| 命令 | 说明 |
|---|---|
| `/money [货币] [玩家]` | 查看余额，不写货币就显示全部。别名：`/bal`、`/balance`、`/qian` |
| `/pay <玩家> <数量> [货币]` | 转账，也可以写成 `/pay <玩家> <货币> <数量>` |
| `/pay confirm` | 确认大额转账 |
| `/pay limit [货币]` | 查看自己今天已接收的转账、剩余额度和税率 |
| `/paylog [玩家] [货币] [all] [页码]` | 查看转账记录。加 `all` 会显示全部流水，包括管理员调整、商店消费等 |
| `/baltop [货币] [页码]` | 余额排行 |
| `/eco give\|take\|set\|reset <玩家> [数量] [货币]` | 管理员调整余额。玩家写 `*` 表示全部在线玩家 |
| `/catereconomy info` | 查看每种货币和存储的状态、税率和限制 |
| `/catereconomy reload` | 重载配置 |
| `/catereconomy import gemseconomy <源货币> <目标货币> [confirm]` | 从 GemsEconomy 导入 |

命令里的"货币"可以写 id、显示名或别名，比如 `miaobi`、`喵币`、`mb` 都指喵币。

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `catereconomy.balance` | 所有人 | 查看自己的余额 |
| `catereconomy.balance.others` | OP | 查看别人的余额 |
| `catereconomy.pay` | 所有人 | 转账 |
| `catereconomy.pay.<货币id>` | — | 显式设为 false 时，禁止这个玩家或用户组转这种货币 |
| `catereconomy.log` | 所有人 | 查看自己的记录 |
| `catereconomy.log.others` | OP | 查看别人的记录 |
| `catereconomy.top` | 所有人 | 查看排行 |
| `catereconomy.admin.eco` | OP | 使用 `/eco` |
| `catereconomy.admin.eco.<货币id>` | — | 显式设为 false 时，禁止用 `/eco` 调整这种货币 |
| `catereconomy.admin` | OP | 重载、查看状态、导入数据 |
| `catereconomy.bypass.cooldown` | OP | 转账没有冷却 |
| `catereconomy.bypass.tax` | 无人 | 转账免税 |
| `catereconomy.bypass.sameip` | 无人 | 不受同 IP 限制 |

免税和同 IP 豁免默认连 OP 也没有，需要的话用 LuckPerms 单独给。

## 变量

需要安装 PlaceholderAPI。下表中的 `catereconomy` 都可以换成 `eco`，效果完全相同，比如 `%catereconomy_have_喵币%` 和 `%eco_have_喵币%` 一样。`<货币>` 可以写 id、显示名或别名。

| 变量 | 示例输出 | 说明 |
|---|---|---|
| `%catereconomy_have_<货币>%` | `1,200` | 当前玩家拥有的数量（带千分位） |
| `%catereconomy_have_raw_<货币>%` | `1200` | 纯数字，用于菜单里比较大小 |
| `%catereconomy_have_formatted_<货币>%` | `1,200 喵币` | 按 `format` 显示，带颜色 |
| `%catereconomy_balance_<货币>%` | `1,200 喵币` | 同 `have_formatted` |
| `%catereconomy_amount_<货币>%` | `1,200` | 同 `have` |
| `%catereconomy_raw_<货币>%` | `1200` | 同 `have_raw` |
| `%catereconomy_name_<货币>%` | `喵币` | 货币显示名 |
| `%catereconomy_tax_<货币>%` | `5` | 转账税率（百分比） |
| `%catereconomy_receive_limit_<货币>%` | `100,000` | 每日接收上限，不限时显示"不限" |
| `%catereconomy_top_name_<货币>_<名次>%` | `Steve` | 排行第 N 名的玩家名 |
| `%catereconomy_top_balance_<货币>_<名次>%` | `9,999` | 排行第 N 名的余额 |

- 余额变量读的是缓存：玩家进服时加载，每隔 `sync.refresh-seconds` 秒从 MySQL 刷新一次，自己的变动会立即更新。
- 排行变量每隔 `top.refresh-seconds` 秒刷新一次。

DeluxeMenus 判断余额够不够的例子：

```yaml
view_requirement:
  requirements:
    enough:
      type: '>='
      input: '%eco_have_raw_喵币%'
      output: '100'
```

## 兼容旧命令和旧变量

**`/eco` 支持的写法**（菜单里的旧命令不用改）：

| 写法 | 来源 |
|---|---|
| `eco give %player_name% 100` | Essentials，使用默认货币 |
| `eco give %player_name% 1 gameadder2` | GemsEconomy，货币写在最后 |
| `eco take %player_name% 50 coins` | GemsEconomy |
| `eco %player_name% give 100` | 玩家名写在前面 |
| `eco give * 100 喵币` | 给全部在线玩家 |
| `add`、`deposit` / `remove`、`withdraw` | 分别等同于 give / take |

GemsEconomy 里的货币名要在 CaterEconomy 里有同名的 id、显示名或别名，比如默认配置给银币加了别名 `coins`。

**旧变量**：开启 `placeholders.gemseconomy-compat` 后，`%gemseconomy_balance_<货币>%` 可以继续用，返回纯数字。这个兼容只在没装 GemsEconomy 时生效，记得同时删掉 `PlaceholderAPI/expansions` 里的 GemsEconomy 扩展。

## 从 GemsEconomy 迁移

1. 在 `config.yml` 的 `import.gemseconomy` 里填写 GemsEconomy 的数据库信息。账号只需要 SELECT 权限，插件不会修改对方的表。
2. 预览：`/catereconomy import gemseconomy coins yinbi`。会显示找到多少个账户、合计多少钱，不写入任何数据。
3. 确认无误后执行：`/catereconomy import gemseconomy coins yinbi confirm`。每个账户在目标货币上的余额会被**覆盖**成 GemsEconomy 里的值，每个账户记一条"数据导入"流水。
4. 每种旧货币都要单独导入一次，比如 `gameadder2`、`achievementpoints`。目标货币要先在配置里建好。
5. 导入完成后，卸掉 GemsEconomy 和它的 PAPI 扩展。

## 数据表

每个存储里有四张表（表名前加 `table-prefix`）：

| 表 | 内容 |
|---|---|
| `balances` | 余额，每个玩家的每种货币一行 |
| `records` | 流水：转账、给予、扣除、设置、重置、Vault 收支、导入 |
| `daily` | 每日计数：收款人当天接收额、同 IP 当天次数和额度，3 天前的自动清理 |
| `ips` | 玩家用过的 IP 和最后使用时间，超过 `window-days` 的自动清理 |

MySQL 需要的权限：目标库的 SELECT、INSERT、UPDATE、DELETE、CREATE。

## 常见问题

**Q：`/catereconomy info` 显示某个存储"不可用"？**
A：看服务器启动日志里 `存储 xxx 启动失败` 后面的原因，一般是 MySQL 地址、账号或密码不对，或者数据库没建。改好后执行 reload。

**Q：同 IP 限制没有效果？**
A：先看后台有没有"玩家 IP 为 127.0.0.1"的提醒。有的话，在子服的 `spigot.yml` 里设置 `bungeecord: true`，同时确认 BungeeCord 的 `ip_forward: true`。

**Q：其他子服给玩家加了钱，这边显示的余额没变？**
A：只有 MySQL 存储的货币会在子服之间共享，最多延迟 `sync.refresh-seconds` 秒。sqlite 存储的货币本来就只属于本服。

**Q：商店插件扣款失败，提示"数据库繁忙"？**
A：Vault 是同步接口，主线程最多等 `vault.timeout-ms` 毫秒。数据库太慢时会超时失败，这时不会扣钱。请检查数据库延迟，必要时适当调大这个值。

**Q：能不能把喵币换成星币（兑换）？**
A：当前版本不支持货币兑换。

## 构建

```bash
mvn clean package
```

成品在 `target/CaterEconomy-1.0.0.jar`。

单元测试用 SQLite 覆盖了以下场景：
- 扣款不会扣成负数，并发扣款不会超扣
- 两种税的计算方式
- 收款方达到余额上限时整笔回滚
- 每日接收上限：多人共用额度、第二天重置、失败的转账不占额度、并发时不超限
- 同 IP 的次数和额度限制、完全禁止，以及旧 IP 过期
- 流水、按名字查找、排行、重置、清理每日计数

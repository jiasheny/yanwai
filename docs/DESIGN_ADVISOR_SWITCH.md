# 顾问切换（狗头军师 / 情圣）改造设计

本文是「让用户自己选顾问」这套改造的完整设计：数据模型、界面、知识资产管线、提示词桥接、
失效规则、测试矩阵与 CI 方案。目标是**两套上游知识各自保持原味、互不污染，由用户逐联系人切换**。

对应分支：`ci/android-build`（首次提交，仅含 CI 与本文档，不含功能代码）。

---

## 1. 一句话目标

在「帮我回 / 找找话题」抽屉里加一个**顾问**选择器，取值 `狗头军师` / `情圣`。
选谁就用谁的方法论生成，两套原文一字不改地打包；身份（对方是谁）继续独立控制分寸。

---

## 2. 现状拆解（为什么要这么改）

当前链路只有一条知识源：

```
点击生成
  └─ ReplyHostUi:745  ReplyKnowledge.load(context, relationship)
        └─ ReplyKnowledgeCatalog.paths(relationship)      // 3～5 个资产路径
              └─ assets/goutoujunshi/**                   // 单源，硬编码前缀
        └─ ReplyKnowledge.load(...) 按 relationship 缓存
  └─ ReplyProtocol.payload(..., knowledge)
        └─ system 提示：产品任务 + "回复逻辑来自狗头军师" + 全部资料
        └─ user 提示：evidence（消息/身份/背景/补充要求/时间）
  └─ ReplyHttpClient → OpenAI 兼容 /chat/completions
  └─ ReplyProtocol.parse → {"replies":[...],"reason":"..."} → ReplySuggestion(1..6 条)
```

关键结论：**「用哪套知识」这件事只由 `ReplyKnowledgeCatalog` 一个 25 行的文件决定**，
所以引入第二源的成本很低；真正的工作量在数据模型、失效规则和提示词桥接上。

现状基线（实测）：

| 项 | 体量 |
|---|---|
| 狗头军师资产 | 46 个文件 / 309 KB |
| 单次注入（通用三件套） | SKILL 9.4 KB + 话术编排 9.3 KB + 接话技巧 10.6 KB ≈ **29.3 KB** |
| 情圣 SKILL.md | **26.5 KB**（含 bash preamble、建档、computer use、WebSearch 等 Android 端无宿主的段落） |
| 情圣 references | 9 份，examples-library 最大 27.9 KB（自带 8 个 `## 阶段` 标题，可切片） |

---

## 3. 三个正交维度

改造的核心是把「一套知识」拆成三个互不干扰的维度，各自独立持久化、独立失效：

| 维度 | 取值 | 回答的问题 | 存放位置 |
|---|---|---|---|
| **顾问** advisor | 军师 / 情圣 | 用谁的方法论 | 随联系人保存 + 全局默认 |
| **身份** relationship | 暗恋/暧昧/恋人/朋友/长辈/… | 该用什么分寸、称呼、边界 | 随联系人保存（已有） |
| **上下文** context | 消息证据 / 长期背景 / 本次补充 / 参考条数 | 具体对着谁、要达成什么 | 已有 |

三者两两正交：`恋人 × 情圣` 和 `恋人 × 军师` 是两种合法组合，都保留。

---

## 4. 数据模型

### 4.1 新增枚举 `ReplyAdvisor`

```kotlin
package dev.jev.wechatmood.reply

/** Selected methodology. Upstream knowledge stays verbatim; the app only picks which set to load. */
enum class ReplyAdvisor(
    val id: String,
    val label: String,
    val sourceUrl: String,
    val revision: String,
    val note: String,
) {
    JUNSHI(
        id = "junshi",
        label = "狗头军师",
        sourceUrl = "https://github.com/shengjidaguai-china/goutoujunshi",
        revision = "6db7354a4002dc7c448a9c87ffdad8132570c9d3",
        note = "通用沟通、情绪回应、家庭与职场",
    ),
    QINGSHENG(
        id = "qingsheng",
        label = "情圣",
        sourceUrl = "https://github.com/tomwong001/qingsheng-skill",
        revision = "fd762df2e0ce4b8ec68b0087ba7178761045bd1a",
        note = "恋爱推进、话术生成、阶段判断",
    );

    companion object {
        val DEFAULT = JUNSHI
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
```

约定：

- `revision` 与现有 `ReplyKnowledge.REVISION` 同义，升级时改这里 + `assets/*/SOURCE.txt`。
- `DEFAULT` 用于旧数据解码和首次使用，**不做静默改写**。
- 不把「身份 → 顾问」的映射写死在枚举里，它属于「默认值策略」，见 4.4。

### 4.2 持久化：`ReplyIdentitySetting` 升到 format 2

现有编码（`ReplyIdentity.kt`）是 `{"format":1,"role":...,"text":...,"roleId":...,"roleRevision":...}`，
`decode` 里 `require(json.getInt("format") == 1)`。加字段后：

```kotlin
data class ReplyIdentitySetting(
    val relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED,
    val customText: String = "",
    val roleId: String? = null,
    val roleRevision: String? = null,
    val advisor: ReplyAdvisor = ReplyAdvisor.DEFAULT,   // 新增
) {
    fun encode(): String = JSONObject()
        .put("format", 2)
        .put("role", relationship.id)
        .put("text", customText)
        .put("roleId", roleId)
        .put("roleRevision", roleRevision)
        .put("advisor", advisor.id)
        .toString()

    companion object {
        fun decode(payload: String): ReplyIdentitySetting {
            require(payload.length <= 1024)
            val json = JSONObject(payload)
            val format = json.getInt("format")
            require(format in 1..2)                                  // 兼容旧行
            return ReplyIdentitySetting(
                relationship = ReplyRelationship.entries.single { it.id == json.getString("role") },
                customText = json.getString("text"),
                roleId = json.optString("roleId").takeIf { it.isNotEmpty() },
                roleRevision = json.optString("roleRevision").takeIf { it.isNotEmpty() },
                advisor = if (format >= 2) ReplyAdvisor.of(json.optString("advisor")) else ReplyAdvisor.DEFAULT,
            )
        }
    }
}
```

要点：

- **不写迁移脚本**：`format 1` 的行读出来就是默认顾问，写回时自然升成 `format 2`。
- 越界的 advisor id（未来版本降级）落回 `DEFAULT`，不让 `single{}` 抛异常炸掉整条链路。
- `payload.length <= 1024` 的上限保持；新字段只有 10 个字符左右。

### 4.3 角色库 `ReplyRole`

角色库条目同样带默认顾问，`ReplyRole.encode/decode` 增加 `advisor` 字段（缺失 → `DEFAULT`）。
`ReplyRoleStore.apply()` 把角色的 advisor 一起写进联系人身份，保证「选一个角色」= 身份 + 背景 + 顾问三件套同时生效。

`AppliedReplyRole` 增加 `advisor`，让调用方拿到完整结果：

```kotlin
class AppliedReplyRole(
    val identity: ReplyIdentitySetting,
    val background: ContactBackground,
    val catalogRevision: Long? = null,
)
```

（`advisor` 已经在 `identity` 里，不重复加字段。）

### 4.4 默认值策略

新增全局设置 `ReplyAdvisorDefault`：`FOLLOW_RELATIONSHIP`（默认）/ `JUNSHI` / `QINGSHENG`。

```kotlin
object ReplyAdvisorDefault {
    /** Only used when a contact has no explicit choice and the user keeps the setting on "follow". */
    fun pick(setting: ReplyAdvisorDefaultSetting, relationship: ReplyRelationship): ReplyAdvisor =
        when (setting) {
            ReplyAdvisorDefaultSetting.JUNSHI -> ReplyAdvisor.JUNSHI
            ReplyAdvisorDefaultSetting.QINGSHENG -> ReplyAdvisor.QINGSHENG
            ReplyAdvisorDefaultSetting.FOLLOW_RELATIONSHIP -> when (relationship) {
                ReplyRelationship.CRUSH,
                ReplyRelationship.FLIRT,
                ReplyRelationship.PARTNER -> ReplyAdvisor.QINGSHENG
                else -> ReplyAdvisor.JUNSHI
            }
        }
}
```

**关键：默认值只在「这个联系人还没选过」时参与，一旦手动选过就写进联系人身份并固定住**，
之后改全局默认不会回头改已有联系人（与现有身份记忆的语义一致）。

### 4.5 缓存键与话题键

| 位置 | 现状 | 改法 |
|---|---|---|
| `ReplyKnowledge` 内存缓存 | `Map<ReplyRelationship, String>` | `Map<Pair<ReplyAdvisor, ReplyRelationship>, String>` |
| `TopicKey` | `fingerprint, limit, relationship, customRelationship, …` | 增加 `advisor: ReplyAdvisor` |
| `RememberedReply` | 记住 identity/背景/条数/选中条目 | 增加 `advisor` |
| `ReplyComposition.canUse` | 逐字段比对 identity/背景/条数 | 增加 advisor 比对 |

**这是最容易出串味 bug 的地方**：漏掉任何一处，切了顾问之后旧结果会被当成新结果复用，
或者情圣的话题组在军师模式下继续「换个话题」。所以四处的改动都要有对应单测。

---

## 5. 知识资产管线

### 5.1 目录结构

```
app/src/main/assets/
  goutoujunshi/                 # 原样保留，一个字节都不改
  qingsheng/
    LICENSE                     # 原样，MIT (c) 2026 tomwong001
    SOURCE.txt                  # 仓库 / pin / 上游版本 / 删除清单 / 每文件 sha256 与体积
    upstream/SKILL.md           # 上游原文，仅供审计与 diff
    skill.md                    # 注入用：整节删除后其余逐字（26.8 KB → 21.2 KB）
    refs/*.md                   # 上游 9 份 references 逐字复制
    slices/*.md                 # 由 refs/examples-library.md 按其自身阶段标题切出
```

为什么同时留 `upstream/SKILL.md` 和 `skill.md`：**「原味」要可审计**。任何人都能 diff 出
`skill.md` 比上游原文少了哪几节，而不是「作者说改过就改过了」。重复的 27 KB 对 APK 体积无所谓；
references 不重复存放，只留一份。

### 5.2 生成脚本 `tools/fetch-qingsheng.ps1`

职责（幂等、可重复运行）：

1. 按 pin 的 commit 从 `raw.githubusercontent.com` 拉取上游 `skill/**` 与 `LICENSE`、`VERSION`；
2. 校验 `upstream/` 与上游 sha256 一致，不一致就失败退出（防止手改上游文件）；
3. 生成 `inject/skill.md`：按**行区间删除**下列段落，其余逐字保留；
4. 生成 `inject/examples-stage*.md`：按 `## 阶段N` 标题切片 `examples-library.md`；
5. 打印每个产物的字节数与「单次注入最坏组合」的总字节数；
6. 把上游 sha256、删除清单、输出体积写进 `SOURCE.txt`。

`skill.md` 的删除清单——**只按标题整节删除，不做段落级编辑**，这是唯一需要人审的规则：

| 整节删除（按标题匹配） | 原因 |
|---|---|
| `## Preamble（每次 skill 加载时自动执行）` | 没有 shell；版本检查与升级流程依赖 bash + `AskUserQuestion`，而言外自己已有「检查更新」 |
| `## 开场白（每次 skill 首次被激活时输出）` | 每次生成都带自我介绍是灾难 |
| `### 第负一步：上下文加载（每次对话最先执行）` | 读写 `~/.qingsheng/` 档案，无文件系统 |
| `### 第负一步 · Gate A：识别档案对象 → 建档（先于任何分析）` | 同上；长期背景由言外的联系人背景承担 |
| `## 上下文归档（每次对话结束时执行）` | 同上，无文件系统 |

其余内容**逐字保留**——包括 computer use 素材采集、WebSearch 约会落地、快捷指令表这些
在 app 里没有宿主的部分。它们的不适用性由桥接段统一声明（第 6 节），而不是靠删原文解决。
这样删除规则只有一条可执行的定义（"按二级/三级标题整节删除"），任何人能复现、能 diff、能审计。

**删除 ≠ 改写**：不碰任何一句措辞、不替换「兄弟/她」这类称呼。视角适配放在提示词桥接层（第 6 节）。

### 5.3 资产路由表 `ReplyKnowledgeCatalog`

```kotlin
object ReplyKnowledgeCatalog {
    fun paths(advisor: ReplyAdvisor, relationship: ReplyRelationship): List<String> =
        when (advisor) {
            ReplyAdvisor.JUNSHI -> junshi(relationship)
            ReplyAdvisor.QINGSHENG -> qingsheng(relationship)
        }

    private fun junshi(relationship: ReplyRelationship): List<String> =   // 现状原样搬过来
        listOf(
            "goutoujunshi/SKILL.md",
            "goutoujunshi/references/practical/实战话术编排器：从一句回复到后续分支.md",
            "goutoujunshi/references/practical/巧妙接话技巧：让沟通更流畅的实用指南.md",
        ) + when (relationship) { /* 原表不变 */ }

    private fun qingsheng(relationship: ReplyRelationship): List<String> {
        val common = listOf("qingsheng/skill.md")
        val specific = when (relationship) {
            ReplyRelationship.CRUSH -> listOf("refs/stages.md", "slices/examples-stage1-2.md")
            ReplyRelationship.FLIRT -> listOf("refs/signals-tools.md", "slices/examples-stage3-4.md")
            ReplyRelationship.PARTNER -> listOf("refs/mindset-concepts.md", "refs/recovery-playbook.md")
            else -> listOf("refs/stages.md", "slices/examples-stage1-2.md")
        }
        return common + specific.map { "qingsheng/$it" }
    }
}
```

情圣 + 非恋爱身份（用户在恋人聊天里手动给朋友选了情圣）不拦，但只加载通用段——**用户的选择永远被尊重**，
产品层面只保证「默认值给出合理建议」。

### 5.4 体积预算（P0a 已实测）

| 路由 | 实测 | 说明 |
|---|---|---|
| 军师最大 | **46.7 KB**（长辈：11-婚姻家庭 + 高情商拒绝） | 现状就有的量级，本次不动 |
| 情圣最大 | **41.9 KB**（暧昧：signals-tools + 阶段3-4 切片） | 整节删除 + 切片让第二源与第一源同量级 |
| 情圣最小 | **31.7 KB**（恋人：mindset-concepts + recovery-playbook） | |
| 全路由上限 | **47.9 KB** | |

单测守卫线设在 **56 KB**（约 17% 余量），作用是拦住「不小心把整本资料全塞进去」这类回归。
`skill.md` 删除前 26.8 KB、删除后 21.2 KB；三份切片分别 9.8 / 11.5 / 7.6 KB。

---

## 6. 提示词工程（最关键的一层）

### 6.1 冲突在哪

| 情圣原文的要求 | 言外的硬约束 | 冲突性质 |
|---|---|---|
| 「2-3 段正文 + 1 个追问」 | `replies` 是**要发出去的消息**，1～6 条 | 输出契约冲突 |
| 「第一句必须显式说出平台 + 阶段定位」 | 同上 | 会把「微信场景，你们在阶段3」当消息发出去 |
| 「必须进入深度分析模式」 | 同上 | 同上 |
| 「长度 ≈ 用户粘贴长度的 30-50%」 | 每条是短消息，不写小作文 | 节奏冲突 |
| 「首次激活输出开场白」 | 每次生成都可能触发 | 灾难 |
| 「兄弟/她」第三人称叙事 | 用户可能是女生、同性、已确立关系 | 视角冲突 |
| 「档案写在 `~/.qingsheng/targets/`」 | 无文件系统 | 能力缺失（已在 5.2 删除） |

### 6.2 桥接段（写进 `ReplyProtocol` 的 system 提示，不改上游）

```text
本次顾问：{advisor.label}。以下是该顾问的原始资料，保留原文措辞与全部方法论。
资料中的流程仪式属于原产品形态，不适用于言外：
不要输出开场白、不要向用户提问、不要提及资料名/skill/文件路径/版本检查/建档，
不要输出「平台 + 阶段」这类定位句作为消息内容，也不要在 replies 里写追问。
资料提到「兄弟」时指用户本人，提到「她」「对方」时指本次会话的联系人；
按本次 relationship 与 contact_background 理解称呼、性别与关系，方法本身不变。
{{advisor == QINGSHENG ? "资料里的话术要转换成能直接复制发出的口语短句，保持它要求的反问、pivot、去 AI 味。" : ""}}
阶段判断、信号解读、教练式建议写进 reason，replies 里只放我真正要发出去的句子。
输出结构、条数、语气词一律以本提示的 JSON 契约与产品任务为准。
```

设计原则：**方法论 100% 来自上游，输出格式 100% 来自言外**，两者的交界只在这一段里，
并且这一段属于言外自己的产品提示，不进 `assets/qingsheng/`，所以「上游原味」的定义依然成立。

### 6.3 其他提示层改动

- 署名行：`你是言外的聊天回复助手，回复逻辑来自${advisor.label}`（现在是硬编码「狗头军师 goutoujunshi」）。
- `reason` 上限从 `take(2000)` 放宽到 `take(4000)`（`ReplyProtocol.parse`），否则情圣的阶段解读会被截断。
- evidence 增加 `advisor` 字段（`{"id":"qingsheng","label":"情圣"}`），和现有 `relationship` 并列，
  便于排查「这条建议是谁给的」。

### 6.4 防污染测试

固定一条样例聊天，跑两个源，断言：

1. `replies` 中不出现 `阶段`、`平台`、`微信场景`、`教练` 等定位词；
2. `replies` 条数在 1～6，且每条 ≤ 60 字；
3. `reason` 允许出现阶段/信号词（证明桥接没有把教练价值一起砍掉）；
4. 两个源的 system 提示里各自包含自己顾问的 label，且**不包含**另一个。

第 1 条是真实跑模型才能验的（需要 key），先做成「提示词字符串断言 + 实机验收清单」两层。

---

## 7. 界面设计

### 7.1 抽屉首行改三控件（复用现有布局逻辑）

现状（`ReplyHostUi.kt:195-204`）：`[身份 ▾] [参考最近 N 条 ▾]`，权重 1 : 1.2。

改成：

```
[ 恋人 ▾ ]   [ 情圣 ▾ ]   [ 最近 100 条 ▾ ]
  身份          顾问           参考范围
  权重 1.0      权重 0.9       权重 1.1
```

- 字号沿用 13sp，沿用 `ellipsize=END`，文案压缩为「最近 100 条 ▾」腾出横向空间；
- 三方都有最小触控高度，大字号下允许换行（沿用现有 `setPadding` 与 wrap 规则）；
- 顾问未选择时显示全局默认推导出来的结果，并标注「默认」小字，避免用户以为已经手选过。

### 7.2 顾问菜单

点开后两项，每项两行：

```
狗头军师
  通用沟通、情绪回应、家庭与职场
情圣
  恋爱推进、话术生成、阶段判断
────────────────────────
查看来源 ↗        查看许可
```

「查看来源」跳 `advisor.sourceUrl`，「查看许可」打开对应 `assets/<source>/LICENSE`（替换现在硬编码的
`assets.open("goutoujunshi/LICENSE")`）。

### 7.3 其他入口

- 标题副标题（`:186`）从 `言外 2.6.9 · 狗头军师` 改成随顾问变化，并且**可点击**打开同一个菜单；
- 设置页「回复」新增「默认顾问」三选（跟随身份 / 军师 / 情圣）；
- 角色库条目显示所属顾问，可编辑；
- 「保存并检测回复」按当前顾问生成示例，检测结果里标注顾问；
- 结果标题区「为什么这样回」显示 `顾问：情圣 · 身份：恋人`。

### 7.4 指令透传（顺带把情圣的原味指令接上）

情圣原设计里 `/急`、`/换一个`、`/挽回`、`/展示面` 是斜杠指令。言外没有斜杠输入框，
但「本次补充」是自由文本，**原样透传即可**：

- 用户写 `/急` → 模型按情圣原文的快速模式走（资料里有定义）；
- 底部按钮「更简短」旁边加一个「换个角度」，拼 `"{补充要求}\n换一个角度重新给，不要换措辞说同样的话"`，
  等价于 `/换一个`（现有 `:793` 就是拼一句 instruction，加按钮即可）。

---

## 8. 失效与缓存矩阵

改任何一项后，哪些东西必须失效（✅ = 失效/需重新生成）：

| 用户动作 | 旧建议 | 话题组 | 知识缓存 | 备注 |
|---|---|---|---|---|
| 切换顾问 | ✅ 标「上次结果」 | ✅ | 不失效 | 两个源各自缓存，来回切不用重读资产 |
| 切换身份 | ✅（现状） | ✅（现状） | 不失效 | 现状行为不变 |
| 改参考条数 | ✅（现状） | ✅（现状） | — | 现状行为不变 |
| 改长期背景 | ✅（现状） | ✅（现状） | — | 现状行为不变 |
| 改本次补充 | ✅（现状） | ✅（现状） | — | 现状行为不变 |
| 升级 APK（资产 revision 变） | ✅ | ✅ | ✅ | 内存缓存随进程重启自然清空 |

「标上次结果」沿用现有语义：结果留在界面上可看、不可复制、需重新生成，不静默混用。

---

## 9. 请求链路（改后）

```
点击生成
  ├─ 读联系人：identity(relationship, customText, roleId, roleRevision, advisor) + background
  ├─ advisor 解析：手选值 > 全局默认策略(跟随身份/固定)
  ├─ ReplyKnowledgeCatalog.paths(advisor, relationship)
  ├─ ReplyKnowledge.load(context, advisor, relationship)   // (advisor, relationship) 缓存
  ├─ ReplyProtocol.payload(..., advisor)
  │     system = 产品任务 + 桥接段(advisor) + 署名(advisor) + 资料
  │     user   = evidence + relationship + advisor + background + direction
  ├─ ReplyHttpClient（40s 超时、1 MiB 上限、禁用重定向转发 Key）
  └─ ReplyProtocol.parse → ReplySuggestion(parts 1..6, reason ≤ 4000)
        └─ ReplyComposition.accept(context, suggestion, ..., advisor)   // advisor 不符则丢弃
```

取消/账号核对/旧结果保护全部沿用现有实现（`ReplySession`、`ownsDrawer()`、`verifyAccount()`），
本次改造不碰并发与生命周期逻辑。

---

## 10. 测试矩阵

| 测试 | 断言 |
|---|---|
| `ReplyAdvisorTest`（新增） | id 唯一；label/sourceUrl/revision 非空；`of(null)` 落默认 |
| `ReplyKnowledgeCatalogTest`（扩） | 顾问 × 身份全组合：路径存在、非空、去重、3～5 份；**单次注入 ≤ 40 KB** |
| `ReplyIdentityTest`（扩） | format 2 往返；**format 1 旧数据可读且顾问为默认**；越界 id 落默认；长度上限 |
| `ReplyRoleStoreTest`（扩） | 角色带顾问往返；旧角色行（无 advisor）解码为默认；`apply()` 同时落地身份+背景+顾问 |
| `ReplyCompositionTest`（扩） | 切顾问 → `canUse == false`；`accept` 时 advisor 不符返回 false；旧结果字段不被覆盖 |
| `TopicTest`（扩） | `TopicKey` 含顾问；切顾问后 `nextTopic()` 返回 false |
| `ReplyProtocolTest`（扩） | 两源署名正确且互不出现；情圣源含桥接禁项；`reason` 4000 上限 |
| 资产校验（新增） | `upstream/**` 的 sha256 与 `SOURCE.txt` 记录一致 |

离线单测只证明契约与资产完整，**真实模型输出质量仍需实机验收**：

1. 同一段暧昧聊天，分别用军师 / 情圣生成，对比口吻与推进方式是否确实不同；
2. 检查情圣输出里没有出现「阶段N」「平台」这类定位句被当成消息；
3. 恋人身份 + 情圣：验证称呼与亲密度跟随真实聊天，而不是资料里的通用范式；
4. 切顾问后旧建议确实不可复制，重新生成后标题标注新顾问；
5. 断网/错误 Key 下两源各自的失败提示一致。

---

## 11. CI / CD

`.github/workflows/android-build.yml`（本次提交的另一个文件）：

- `ubuntu-latest` + JDK 17（AGP 8.5.2 兼容区间）+ Android SDK 35 + **Gradle 8.9 由 action 提供**
  （仓库故意不提交 wrapper，这里也不新增二进制）；
- 单测与 debug 构建一条命令跑完：`gradle :app:testDebugUnitTest :app:assembleDebug`；
- 产物 `yanwai-debug-apk` 与测试报告作为 artifact 上传；
- 缓存 debug keystore，保证**跨运行签名稳定**，新 APK 能直接覆盖安装（否则每次 runner 换一把新
  debug key，装更新必须先卸载）；
- push tag `v*` 时自动建 Release 并附 APK。

注意事项：

- GitHub 托管 runner 直连 `dl.google.com` / `mavenCentral`，**不需要你本机那个代理**；
- 公开仓库的 Actions 分钟数免费；首次冷构建约 5～10 分钟，之后走缓存更快；
- `settings.gradle.kts` 把阿里云镜像排在 `google()` 前面，美区 runner 上可能偏慢；
  如果第一次跑得离谱，我加一个 `--init-script` 重排仓库顺序（只影响 CI，不改项目文件）；
- fork 若提示「workflows aren't being run」，需要在仓库 Settings → Actions 放行，或用 API 打开。

签名演进：P0 用 debug 签名（够实机装）；P1 用你自建 keystore 存成 Actions secret 签 release 包；
P2 tag 自动发版（已内置）。

---

## 12. 分期

| 期 | 内容 | 产出 |
|---|---|---|
| **P0** | `ReplyAdvisor` + 联系人持久化(format 2) + catalog 双源 + 桥接段 + 抽屉三控件 + 双源资产与脚本 + 测试 | 可安装 APK，两个顾问都能用 |
| **P1** | 默认顾问设置、角色库带顾问、许可弹窗双源、`/急`·`/换个角度` 按钮、`reason` 界面呈现优化 | 体验完整 |
| **P2** | 教学模式 v1：只拆解 / 先猜后给 / 批改 + 骨架化拆解 + `drill` 卡片（见 [教学模式设计](DESIGN_LEARNING_MODE.md)） | 边用边学 |
| **P3** | 复盘层：记录落库 + 结果回填 + 错题本 + 间隔重复 + APP「学习」页 | 有闭环反馈 |
| **P4** | 「完整模式」档位、情圣 7 阶段选择器注入 evidence、话题生成改用 pivot 原则、release 签名 | 深度可用 |

---

## 13. 风险与对策

| 风险 | 后果 | 对策 |
|---|---|---|
| 缓存键漏加 advisor | 两源结果串味（最危险） | 四类键同时改 + 专项单测（第 10 节） |
| 桥接不足 | 阶段定位被当成消息发出去 | 桥接禁项 + 样例断言 + 实机清单第 2 条 |
| 情圣资料体积 | token 成本、响应变慢 | 删段版 + 阶段切片 + ≤40 KB 预算断言 |
| 上游改版/迁移 | pin 失效、脚本跑不动 | pin commit + SOURCE.txt 记录 + 脚本可复现 |
| 阿里云优先镜像在美区 CI 慢 | 构建时间长 | 首次实测后决定是否加 init script 重排 |
| 视角/性别错位（资料写「兄弟/她」） | 回复串味 | 桥接层映射，**不改上游一个字** |
| 恋人长期关系不是情圣强项 | P0 情报下建议偏「追」 | 默认策略对 PARTNER 可用军师；P2 做双源加载 |
| 版本号规则 | 本地提交被 hook 拦 | 功能提交走 `tools/bump-version.ps1`（CI/文档提交不动版本号） |

---

## 14. 待拍板

1. **默认顾问**：`跟随身份`（恋爱类默认情圣）还是干脆全部默认军师？
2. **PARTNER（恋人）默认给谁**：情圣偏「推进」，长期关系里军师可能更稳。
3. **P2 的「完整模式」**要不要——它是唯一会明显抬高 token 成本的功能。
4. 走完 P0 之后，是**只留在你的 fork**，还是整理成 PR 提给上游 `YIRC99/yanwai`。

教学模式（第二个需求）的设计与待拍板项见 [DESIGN_LEARNING_MODE.md](DESIGN_LEARNING_MODE.md)。

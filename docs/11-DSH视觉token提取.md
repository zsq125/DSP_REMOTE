# 11 · DSH 视觉 Token 提取（需求 4 对齐依据）

> **来源**：直接从 DSH 前端产物提取，非猜测。
> 提取位置：
> - `dsh-client-ui-theme/lib/client.js`（token 定义集中处）
> - `dsh-client-ui-conversation/lib/client.js`
> - `dsh-web-frontend/dist/assets/index-DUvMhLle.css`
> - `dsh-client-ui-primitives/lib/*.module.css`（组件级尺寸）
>
> 路径前缀：`E:\system\node_env\npm-global\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai\`

## 一、字体（最关键的 20 个）

### 1.1 字族
```css
--dsw-font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", …;
```
**⇒ Android 对齐建议**：`FontFamily.Default`（系统字体），中文走系统 `PingFang SC`/`HarmonyOS Sans` 等。**不要硬编码自定义字体**。

### 1.2 字号阶梯（DSH 的语义化命名：xxxs→xl）

| Token | 字号/行高 | 字重 | 用途 |
|---|---|---|---|
| `--dsw-font-xxxs-11` | 11px / 14px | 400 | 最小标签 |
| `--dsw-font-xxxs-strong-11` | 11px / 14px | 500 | 上述加粗 |
| `--dsw-font-xxs-12` | 12px / 18px | 400 | 次要说明 |
| `--dsw-font-xxs-strong-12` | 12px / 18px | 500 | |
| `--dsw-font-xs-13` | 13px / 20px | 400 | **UI 标签/折叠行标题** |
| `--dsw-font-xs-strong-13` | 13px / 20px | 500 | |
| `--dsw-font-s-14` | 14px / 22px | 400 | **次级正文** |
| `--dsw-font-s-strong-14` | 14px / 22px | 500 | |
| `--dsw-font-base-16` | 16px / 24px | 400 | **正文** |
| `--dsw-font-base-strong-16` | 16px / 24px | 500 | |
| `--dsw-font-m-18` | 16px / 28px | 500 | 中标题 |
| `--dsw-font-l-20` | 20px / 28px | 500 | 大标题 |
| `--dsw-font-xl-24` | 24px / 32px | 600 | 特大标题 |

### 1.3 Markdown 专用（对话区正文走这套）

| Token | 值 |
|---|---|
| `--dsw-font-markdown-base` | `14px / 24px`（受 `--dsh-content-font-size` 控制，可被设置调大） |
| `--dsw-font-markdown-base-strong` | `600 14px / 24px` |
| `--dsw-font-markdown-h1` | `700 21px / 30px` |
| `--dsw-font-markdown-h2` | `700 19px / 28px` |
| `--dsw-font-markdown-h3` | `700 18px / 26px` |
| `--dsw-font-markdown-h4` | `600 14px / 24px` |
| `--dsw-font-markdown-small` | `12px / 20px` |
| `--dsw-font-markdown-code` | `12px / 19px` **`var(--ds-font-family-code)`（等宽）** |
| `--dsw-font-markdown-code-block` | `11px / 19px`（等宽） |
| `--dsw-font-markdown-table` | `13px / 22px` |
| `--dsw-font-markdown-table-head` | `500 13px / 22px` |

**⚠️ 注意**：DSH 的正文是 **14px/24px**（不是 16px），且**行高 24px 很宽松**。这直接决定"读起来像不像 DSH"。

## 二、圆角

```css
--dsw-radius-xs:    4px
--dsw-radius-sm:    8px
--dsw-radius-md:    12px
--dsw-radius-lg:    16px
--dsw-radius-xl:    20px
--dsw-radius-panel: 28px
--dsw-corner-shape: superellipse(1.5)   /* ★ 连续圆角（Android 无法直接复刻，可用 RoundedCornerShape 近似） */
```

## 三、颜色（三级体系：alias → static）

**结构**：`--dsw-alias-*` 引用 `--dsw-static-*`，业务只该用 alias。

### 3.1 文字色（alias/label）
```css
--dsw-alias-label-primary:            var(--dsw-static-neutral-bluish-1000)   /* 主文字 */
--dsw-alias-label-secondary:          var(--dsw-static-neutral-bluish-700)
--dsw-alias-label-tertiary:           var(--dsw-static-neutral-bluish-600)
--dsw-alias-label-caption:            var(--dsw-static-neutral-bluish-400)
--dsw-alias-label-dimmed:             var(--dsw-static-neutral-bluish-200)
--dsw-alias-label-primary-foreground: var(--dsw-static-neutral-bluish-00)    /* 反色（深底上） */
```

### 3.2 背景/边框（alias）
```css
--dsw-alias-bg-base:      var(--dsw-static-neutral-bluish-00)
--dsw-alias-bg-layer-1/2/3
--dsw-alias-bg-overlay:   var(--dsw-static-neutral-bluish-150)
--dsw-alias-bg-skeleton:  #0000000a
--dsw-alias-border-l1:    #0000000a      /* 最淡 */
--dsw-alias-border-l2:    #0000001a
--dsw-alias-border-l3:    #0000001f
--dsw-alias-border-l4:    #00000029      /* 最重 */
```

### 3.3 品牌色（deepseek 系列）
```css
--dsw-static-deepseek-100: #e4edfd
--dsw-static-deepseek-200: #d3e2ff
--dsw-static-deepseek-300: #b7c8fe
--dsw-static-deepseek-400: #7aaaff
--dsw-static-deepseek-450: #5686fe
/* 气泡 */
--dsw-specific-bubble:           var(--dsw-static-deepseek-50)
--dsw-specific-bubble-highlight: var(--dsw-static-deepseek-200)
```

### 3.4 功能色
```css
--dsw-static-amber-500: #f59e0b   /* 警告 */
--dsw-static-amber-600: #dd8629
--dsw-static-blue-500:  #3b82f6
--dsw-static-blue-600:  #2563eb
--dsw-static-blue-900:  #0e3074
```

## 四、阴影 / 高度

```css
--dsw-shadow-lv1:     0 2px 4px 0 #0000000d
--dsw-shadow-lv1-blur:0 4px 12px 0 #00000005
--dsw-shadow-lv2:     0 4px 12px 0 #00000005, 0 2px 8px 0 #0000000a
--dsw-shadow-lv3:     0 0 1px 0 #0003, 0 0 4px 0 #00000005, 0 12px 32px 0 #00000014
--dsw-elevation-stroke: 0 0 0 .5px var(--dsw-elevation-stroke-color)
--dsw-elevation-panel:  … 0 3px 8px 0 #00000008, 0 0 16px 0 #00000005
```

## 五、【与需求 3 直接相关】折叠行（DisclosureRow）的真实尺寸

**来源**：`dsh-client-ui-primitives/lib/DisclosureRow.module.css`（原文注释）

```css
/* Shared disclosure header: [16px leading] gap 6 [title 13/24] at the default size. */
.row {
  display: flex;
  align-items: center;
  height: calc(24px + var(--dsh-content-font-delta, 0px));   /* ★ 行高 24px */
}
.leading {           /* 左侧图标（▸/▾） */
  width: calc(16px + var(--dsh-content-font-delta, 0px));    /* ★ 16px 方框 */
  height: calc(16px + var(--dsh-content-font-delta, 0px));
  display: inline-flex; align-items: center; justify-content: center;
  margin-right: 6px;                                        /* ★ 图标与标题间距 6px */
}
```

**⇒ 折叠行的精确规格**：
- **行高 24px**
- **左侧图标 16×16px，与标题间距 6px**
- **标题字号 13px / 行高 20px**（`--dsw-font-xs-13`）
- **`data-expandable` 时才 `cursor: pointer`**（即：**不可展开的行不该有手型**——这点 App 也要照做）

## 六、其他

```css
--dsw-focus-ring-width: 2px
--dsw-menu-backdrop-filter: blur(40px) saturate(150%)
--dsw-linear-gradient-think: linear-gradient(180deg, #fff 20.19%, #fff0 100%)   /* 思考区渐隐 */
--dsw-linear-think-select:   linear-gradient(180deg, #f5f6f7 20.19%, #f5f6f700 100%)
```

## 七、给 App 的落地建议（按优先级）

| 优先级 | 项 | 做法 |
|---|---|---|
| **P0** | **正文 14px / 行高 24px** | 这是"像不像 DSH"的第一观感。Compose: `fontSize = 14.sp`，`lineHeight = 24.sp` |
| **P0** | **折叠行规格** | 行高 24dp、图标 16dp、间距 6dp、标题 13sp/20sp |
| **P0** | **等宽代码 12px/19px** | `FontFamily.Monospace`，`fontSize = 12.sp`，`lineHeight = 19.sp` |
| **P1** | **圆角阶梯** 4/8/12/16/20/28 | Compose `RoundedCornerShape` |
| **P1** | **文字色三级**（primary/secondary/tertiary） | 从 `deepseek-*` 与 `neutral-bluish-*` 取色 |
| **P1** | **边框 4 级**（l1~l4） | 0x0A~0x29 的黑色透明度 |
| **P2** | **阴影 lv1~lv3** | Compose `Modifier.shadow(elevation, shape)` 近似 |
| **P3** | `superellipse(1.5)` 连续圆角 | **Android 无原生支持**，用普通圆角近似并**在交付里注明差异** |

## 八、未提取到的（需另查）

- **`--ds-font-family-code` 的具体字族**（只知它是等宽，未拿到值）
- **暗色主题**的 alias 覆盖值（DSH 有 light/dark 两套，本次只提取到 light）
- **动效时长/缓动曲线**（不在 CSS 变量里，需从组件 JS 找）

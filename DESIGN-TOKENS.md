# MongoDB design tokens — authoritative

These are **not** approximations. They were extracted from the published
`@leafygreen-ui/palette` (v5.0.2) and `@leafygreen-ui/tokens` npm packages —
MongoDB's own design system, the one mongodb.com and Atlas are built from.
Use these exact values.

## Palette

```
white        #FFFFFF
black        #001E2B    <- MongoDB "black" is a deep blue-teal, NOT #000
```

### Gray
| token | hex |
|---|---|
| gray.dark4 | `#112733` |
| gray.dark3 | `#1C2D38` |
| gray.dark2 | `#3D4F58` |
| gray.dark1 | `#5C6C75` |
| gray.base  | `#889397` |
| gray.light1| `#C1C7C6` |
| gray.light2| `#E8EDEB` |
| gray.light3| `#F9FBFA` |

### Green (the brand)
| token | hex | typical use |
|---|---|---|
| green.dark3 | `#023430` | dark surfaces |
| green.dark2 | `#00684A` | **primary button fill**, links on light |
| green.dark1 | `#00A35C` | hover |
| green.base  | `#00ED64` | the famous "spring green" — accents, indicators, dark-mode text accents |
| green.light1| `#71F6BA` | |
| green.light2| `#C0FAE6` | subtle fills |
| green.light3| `#E3FCF7` | tinted backgrounds |

### Semantic
| token | hex |
|---|---|
| blue.dark2 `#083C90` · blue.base `#016BF8` · blue.light2 `#C3E7FE` · blue.light3 `#E1F7FF` |
| red.dark2 `#970606` · red.base `#DB3030` · red.light2 `#FFCDC7` · red.light3 `#FFEAE5` |
| yellow.dark2 `#944F01` · yellow.base `#FFC010` · yellow.light2 `#FFEC9E` · yellow.light3 `#FEF7DB` |
| purple.dark2 `#5E0C9E` · purple.base `#B45AF2` · purple.light3 `#F9EBFF` |

## Typography — exact LeafyGreen stacks

```css
--font-sans:  'Euclid Circular A', 'Helvetica Neue', Helvetica, Arial, sans-serif;
--font-serif: 'MongoDB Value Serif', 'Times New Roman', serif;
--font-code:  'Source Code Pro', Menlo, monospace;
```

**Euclid Circular A is proprietary and must NOT be fetched from a CDN** (the demo
has to run offline). Declare the stack verbatim anyway — that is MongoDB's own
fallback chain, and Helvetica Neue on macOS is a close, honest substitute. Do not
substitute a different family or add a Google Font.

Weights used by LeafyGreen: `regular` (400), `medium` (500), `semibold` (600), `bold` (700).

## Applying it

MongoDB's own surfaces are predominantly **light**: `#FFFFFF` cards on a
`#F9FBFA` (gray.light3) page, `#E8EDEB` (gray.light2) hairline borders, body copy
in `gray.dark3`/`gray.dark2`, headings in `black` (`#001E2B`). Green is used
**sparingly and with intent** — primary actions in `green.dark2`, live/success
state in `green.base`. Radii are modest (roughly 4–12px, cards ~12px, buttons
~6px), shadows are low-opacity and subtle, and there is a lot of whitespace.

Dark mode inverts onto `black` `#001E2B` and `gray.dark4/dark3` surfaces, with
`green.base` as the accent (it is designed to sit on the dark teal).

The single most common way to get this wrong is to splash `#00ED64` everywhere.
On mongodb.com, most of the page is white, near-black text, and grey rules — the
green is a punctuation mark, not the paint.

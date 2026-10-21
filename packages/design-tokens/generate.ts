import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// Generates native token files for SwiftUI and Compose from tokens.json, and the Bloom icon set
// (icons/*.svg) as Android vector drawables and an iOS asset catalog. Fails if any declared
// foreground/background pair, or any palette family's ink on its fill, misses its WCAG contrast
// target in light, dark, or either increased-contrast variant.
// Run: npm run generate:tokens (CI can run it with --check).

interface Pair { light: string; dark: string; lightHc?: string; darkHc?: string }
interface FamilyMode { fill: string; ink: string }
interface Family { light: FamilyMode; dark: FamilyMode; source?: string }
interface Spring { response: number; damping: number }
interface TypeRole { use: string; size: number; line: number; weight: number; tracking: number; maxScale?: number; family?: 'condensed'; uppercase?: boolean; ios: string; iosWeight: string; iosSize?: number }
interface Tokens {
  color: Record<string, Pair>;
  palette: { families: Record<string, Family>; avatars: string[] };
  illustration: Record<string, string>;
  contrast: { fg: string; bg: string; min: number; note?: string }[];
  spacing: Record<string, number>;
  radius: Record<string, number>;
  touchTarget: { iosPoints: number; androidDp: number; alarmControlMin: number; controlHeight: number };
  layout: Record<string, number | string>;
  component: Record<string, number | string>;
  type: { roles: Record<string, TypeRole> };
  opacity: Record<string, number>;
  elevation: Record<string, number | string>;
  motion: {
    durationMs: Record<string, number>;
    spring: Record<string, Spring | string>;
    pressScale: number;
    staggerMs: number;
    staggerMaxItems: number;
  };
}

const root = new URL('../../', import.meta.url);
const tokens = JSON.parse(readFileSync(new URL('packages/design-tokens/tokens.json', root), 'utf8')) as Tokens;

function channels(hex: string): [number, number, number] {
  const n = Number.parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => c / 255) as [number, number, number];
}
const linear = (s: number) => (s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4);

function luminance(hex: string): number {
  const ch = channels(hex).map(linear);
  return 0.2126 * ch[0]! + 0.7152 * ch[1]! + 0.0722 * ch[2]!;
}

export function contrast(a: string, b: string): number {
  const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (l1 + 0.05) / (l2 + 0.05);
}

/** OKLCH (Björn Ottosson's OKLab), reported so perceptual relationships between roles stay visible. */
export function oklch(hex: string): [number, number, number] {
  const [r, g, b] = channels(hex).map(linear) as [number, number, number];
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
  const A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
  const B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
  return [L, Math.hypot(A, B), ((Math.atan2(B, A) * 180) / Math.PI + 360) % 360];
}
const lch = (hex: string) => {
  const [L, C, h] = oklch(hex);
  return `${(L * 100).toFixed(1)} ${C.toFixed(3)} ${C < 0.02 ? '–' : h.toFixed(0)}`;
};

const modes = ['light', 'dark', 'lightHc', 'darkHc'] as const;
type Mode = (typeof modes)[number];
/** Increased-contrast variants fall back to the normal value when a token doesn't define one. */
const value = (p: Pair, m: Mode): string => (m === 'lightHc' ? p.lightHc ?? p.light : m === 'darkHc' ? p.darkHc ?? p.dark : p[m]);

const report: string[] = [
  '# Contrast report (generated)',
  '',
  'Every pair is checked in light, dark, and both increased-contrast variants (iOS Increase Contrast; Android 14+ contrast level ≥ medium).',
  '',
  '| Pair | Light | Dark | Light HC | Dark HC | Minimum | Result |',
  '|---|---:|---:|---:|---:|---:|---|',
];
let failed = false;
for (const c of tokens.contrast) {
  const fg = tokens.color[c.fg];
  const bg = tokens.color[c.bg];
  if (!fg || !bg) throw new Error(`Unknown colour in contrast pair ${c.fg} on ${c.bg}`);
  const ratios = modes.map((m) => contrast(value(fg, m), value(bg, m)));
  const ok = ratios.every((r) => r >= c.min);
  if (!ok) failed = true;
  report.push(`| ${c.fg} on ${c.bg}${c.note ? ` (${c.note.split(':')[0]})` : ''} | ${ratios.map((r) => r.toFixed(2)).join(' | ')} | ${c.min} | ${ok ? 'pass' : '**FAIL**'} |`);
}
const families = Object.entries(tokens.palette.families);
for (const [name, f] of families) {
  // Families have no increased-contrast variant: their inks already clear 4.5:1.
  const ratios = [contrast(f.light.ink, f.light.fill), contrast(f.dark.ink, f.dark.fill)];
  const ok = ratios.every((r) => r >= 4.5);
  if (!ok) failed = true;
  report.push(`| ${name} ink on ${name} fill (palette) | ${ratios[0]!.toFixed(2)} | ${ratios[1]!.toFixed(2)} | – | – | 4.5 | ${ok ? 'pass' : '**FAIL**'} |`);
}
for (const a of tokens.palette.avatars) if (!tokens.palette.families[a]) throw new Error(`Unknown avatar family ${a}`);
report.push('', '## Roles in OKLCH (L × 100, C, h)', '', '| Token | Light | Dark |', '|---|---|---|');
for (const [k, v] of Object.entries(tokens.color)) report.push(`| ${k} | \`${v.light}\` ${lch(v.light)} | \`${v.dark}\` ${lch(v.dark)} |`);
report.push('', '## Palette families (fill / ink)', '', '| Family | Light | Dark | Source |', '|---|---|---|---|');
for (const [k, f] of families) report.push(`| ${k} | \`${f.light.fill}\` / \`${f.light.ink}\` | \`${f.dark.fill}\` / \`${f.dark.ink}\` | ${f.source ?? ''} |`);

const camel = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const hex = (h: string) => `0x${h.slice(1).toUpperCase()}`;
const springs = Object.entries(tokens.motion.spring).filter((e): e is [string, Spring] => typeof e[1] !== 'string');
const elevation = tokens.elevation as Record<string, number>;
/** Android's stiffness for an iOS-style response (the spring's period in seconds). */
const stiffness = (response: number) => ((2 * Math.PI) / response) ** 2;
const hasHc = (p: Pair) => p.lightHc !== undefined || p.darkHc !== undefined;
const illustration = Object.entries(tokens.illustration).filter(([k]) => k !== 'note');
const typeRoles = Object.entries(tokens.type.roles);
const layout = tokens.layout as Record<string, number>;
/** Numeric entries of a token group (skips its "note"). */
const numeric = (group: Record<string, number | string>) => Object.entries(group).filter((e): e is [string, number] => typeof e[1] === 'number');

// ------------------------------------------------------------------ icons
interface Icon { file: string; res: string; title: string; paths: string[] }
const iconDir = new URL('packages/design-tokens/icons/', root);
const icons: Icon[] = readdirSync(iconDir)
  .filter((f) => f.endsWith('.svg'))
  .sort()
  .map((file) => {
    const svg = readFileSync(new URL(file, iconDir), 'utf8');
    const paths = [...svg.matchAll(/<path d="([^"]+)"\/>/g)].map((m) => m[1]!);
    if (paths.length === 0) throw new Error(`No paths in icons/${file}`);
    return { file, res: `wm_${file.replace('.svg', '').replaceAll('-', '_')}`, title: /<title>([^<]+)<\/title>/.exec(svg)?.[1] ?? '', paths };
  });
const words = (i: Icon) => i.file.replace('.svg', '').split('-');
/** Icons that point along the reading direction flip in right-to-left languages. */
const directional = new Set(['back', 'chevron-right', 'send', 'arrow-up-right', 'logout']);
const mirrors = (i: Icon) => directional.has(i.file.replace('.svg', ''));
const kotlinIconName = (i: Icon) => words(i).map(camel).join('');
const swiftKeywords = new Set(['repeat', 'return', 'switch', 'case', 'default', 'import', 'class', 'struct', 'enum', 'in', 'is', 'as', 'do', 'if', 'else', 'for', 'while', 'guard', 'defer', 'throw', 'try', 'catch', 'init', 'self', 'super', 'true', 'false', 'nil', 'func', 'let', 'var']);
const swiftIconName = (i: Icon) => {
  const [first, ...rest] = words(i);
  const name = first! + rest.map(camel).join('');
  return swiftKeywords.has(name) ? `\`${name}\`` : name;
};

/**
 * Android path data with every number written out (`0.7`, not `.7`; `1.8 0.7`, not `1.8.7`), as
 * lint's InvalidVectorPath check and older path parsers require. The SVG sources stay compact.
 */
function fullNumbers(d: string): string {
  let out = '';
  let afterCommand = false;
  for (const [, command, number] of d.matchAll(/([MmLlHhVvCcSsQqTtAaZz])|(-?(?:\d*\.\d+|\d+\.?))/g)) {
    if (command) {
      out += (out ? ' ' : '') + command;
      afterCommand = true;
    } else {
      out += (afterCommand ? '' : ' ') + String(Number.parseFloat(number!));
      afterCommand = false;
    }
  }
  return out;
}

const androidVector = (i: Icon) => `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by packages/design-tokens/generate.ts from icons/${i.file}. Do not edit by hand. -->
<!-- ${i.title} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"${mirrors(i) ? '\n    android:autoMirrored="true"' : ''}>
${i.paths.map((d) => `    <path
        android:fillColor="#00000000"
        android:pathData="${fullNumbers(d)}"
        android:strokeColor="#FF000000"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeWidth="2" />`).join('\n')}
</vector>
`;

const iosSvg = (i: Icon) => `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#000000" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
${i.paths.map((d) => `  <path d="${d}"/>`).join('\n')}
</svg>
`;

const iosImageSet = (i: Icon) => `${JSON.stringify(
  {
    images: [{ filename: `${i.res}.svg`, idiom: 'universal', ...(mirrors(i) ? { 'language-direction': 'left-to-right' } : {}) }],
    info: { author: 'xcode', version: 1 },
    properties: { 'preserves-vector-representation': true, 'template-rendering-intent': 'template' },
  },
  null,
  2,
)}\n`;

const swiftIcons = `// Generated by packages/design-tokens/generate.ts. Do not edit by hand.
import SwiftUI

/// The Bloom icon set (packages/design-tokens/icons): 24-unit grid, 2-unit rounded strokes, stored
/// as template vectors in Icons.xcassets so they take the foreground colour. Android draws the
/// same SVGs as vector drawables (\`WMIcon\` in Icons.kt).
enum WMIcon: String, CaseIterable, Sendable {
${icons.map((i) => `    /// ${i.title}\n    case ${swiftIconName(i)} = "${i.res}"`).join('\n')}

    @MainActor var image: Image { Image(rawValue) }
}
`;

const kotlinIcons = `// Generated by packages/design-tokens/generate.ts. Do not edit by hand.
package app.wakeme.designsystem

import androidx.annotation.DrawableRes
import app.wakeme.R

/**
 * The Bloom icon set (packages/design-tokens/icons): 24-unit grid, 2-unit rounded strokes, as
 * vector drawables tinted by the caller. iOS draws the same SVGs (\`WMIcon\` in Icons.generated.swift).
 */
enum class WMIcon(@DrawableRes val res: Int) {
${icons.map((i) => `    /** ${i.title} */\n    ${kotlinIconName(i)}(R.drawable.${i.res}),`).join('\n')}
}
`;

// ------------------------------------------------------------------ Swift
const swiftFamily = (f: Family) => `WMFamily(fill: Color(light: ${hex(f.light.fill)}, dark: ${hex(f.dark.fill)}), ink: Color(light: ${hex(f.light.ink)}, dark: ${hex(f.dark.ink)}))`;
const swiftFont = (r: TypeRole) => {
  const base = r.iosSize ? `.system(size: ${r.iosSize}, weight: .${r.iosWeight})` : `.${r.ios}.weight(.${r.iosWeight})`;
  return r.family === 'condensed' ? `${base}.width(.condensed)` : base;
};

const swift = `// Generated by packages/design-tokens/generate.ts. Do not edit by hand.
import SwiftUI
import UIKit

enum WMColor {
${Object.entries(tokens.color)
  .map(([k, v]) =>
    hasHc(v)
      ? `    static let ${k} = Color(light: ${hex(v.light)}, dark: ${hex(v.dark)}, lightHighContrast: ${hex(value(v, 'lightHc'))}, darkHighContrast: ${hex(value(v, 'darkHc'))})`
      : `    static let ${k} = Color(light: ${hex(v.light)}, dark: ${hex(v.dark)})`,
  )
  .join('\n')}
}

/// A petal family: \`fill\` for tiles, chips and avatars, \`ink\` for text and icons on it (≥ 4.5:1
/// in both themes). Families mark things of the same kind; they never carry status.
struct WMFamily: Sendable {
    let fill: Color
    let ink: Color
}

enum WMPalette {
${families.map(([k, f]) => `    static let ${k} = ${swiftFamily(f)}`).join('\n')}
    /// Friend avatars, in the same order as Android, so a friend has one colour on both.
    static let avatars: [WMFamily] = [${tokens.palette.avatars.join(', ')}]
}

/// Illustration colours for the duck; the same in both themes.
enum WMIllustration {
${illustration.map(([k, v]) => `    static let ${k} = Color(light: ${hex(v)}, dark: ${hex(v)})`).join('\n')}
}

enum WMSpacing {
${Object.entries(tokens.spacing).map(([k, v]) => `    static let ${k}: CGFloat = ${v}`).join('\n')}
}

enum WMRadius {
${Object.entries(tokens.radius).map(([k, v]) => `    static let ${k}: CGFloat = ${v}`).join('\n')}
}

enum WMSize {
    static let minTouchTarget: CGFloat = ${tokens.touchTarget.iosPoints}
    static let alarmControl: CGFloat = ${tokens.touchTarget.alarmControlMin}
    static let controlHeight: CGFloat = ${tokens.touchTarget.controlHeight}
}

/// Semantic type roles. Each follows Dynamic Type through its text style; roles with a fixed
/// design size (iosSize) are for views that scale it with @ScaledMetric(relativeTo:).
enum WMFont {
${typeRoles.map(([k, r]) => `    /// ${r.use}\n    static var ${k}: Font { ${swiftFont(r)} }`).join('\n')}
}

/// Semantic spacing (4-point scale) and component sizes.
enum WMLayout {
    static let screenMargin: CGFloat = ${layout.screenMarginIos}
${numeric(tokens.layout).filter(([k]) => !k.startsWith('screenMargin')).map(([k, v]) => `    static let ${k}: CGFloat = ${v}`).join('\n')}
${numeric(tokens.component).map(([k, v]) => `    static let ${k}: CGFloat = ${v}`).join('\n')}
}

enum WMOpacity {
${Object.entries(tokens.opacity).map(([k, v]) => `    static let ${k}: Double = ${v}`).join('\n')}
}

/// Cards and tiles are flat; only overlays cast a shadow.
enum WMElevation {
    static let overlayShadowOpacity: Double = ${elevation.overlayShadowOpacity}
    static let overlayShadowRadius: CGFloat = ${elevation.overlayShadowRadius}
    static let overlayShadowY: CGFloat = ${elevation.overlayShadowY}
}

/// Raw motion values; Motion.swift turns them into animations that respect Reduce Motion.
enum WMMotionToken {
${Object.entries(tokens.motion.durationMs).map(([k, v]) => `    static let ${k}: Double = ${v / 1000}`).join('\n')}
${springs.map(([k, s]) => `    static let ${k}Response: Double = ${s.response}\n    static let ${k}Damping: Double = ${s.damping}`).join('\n')}
    static let pressScale: CGFloat = ${tokens.motion.pressScale}
    static let stagger: Double = ${tokens.motion.staggerMs / 1000}
    static let staggerMaxItems = ${tokens.motion.staggerMaxItems}
}

extension Color {
    /// A colour that follows light/dark appearance and, where a token defines one, Increase Contrast.
    init(light: UInt32, dark: UInt32, lightHighContrast: UInt32? = nil, darkHighContrast: UInt32? = nil) {
        self.init(uiColor: UIColor { traits in
            let high = traits.accessibilityContrast == .high
            let v = traits.userInterfaceStyle == .dark ? (high ? darkHighContrast ?? dark : dark) : (high ? lightHighContrast ?? light : light)
            return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        })
    }
}
`;

// ------------------------------------------------------------------ Kotlin
const kotlinPalette = (m: Mode) => Object.entries(tokens.color).map(([k, v]) => `        ${k} = Color(0xFF${value(v, m).slice(1).toUpperCase()}),`).join('\n');
const kotlinColor = (h: string) => `Color(0xFF${h.slice(1).toUpperCase()})`;
const kotlinFamilies = (m: 'light' | 'dark') => families.map(([k, f]) => `        ${k} = WakeMeFamily(fill = ${kotlinColor(f[m].fill)}, ink = ${kotlinColor(f[m].ink)}),`).join('\n');
const kotlinStyle = (r: TypeRole) =>
  `TextStyle(fontSize = ${r.size}.sp, lineHeight = ${r.line}.sp, fontWeight = FontWeight.W${r.weight}, letterSpacing = ${(r.tracking * r.size).toFixed(2)}.sp, fontFeatureSettings = "tnum"${r.family === 'condensed' ? ', fontFamily = CondensedFontFamily' : ''})`;

const kotlin = `// Generated by packages/design-tokens/generate.ts. Do not edit by hand.
package app.wakeme.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class WakeMeColors(
${Object.keys(tokens.color).map((k) => `    val ${k}: Color,`).join('\n')}
)

/**
 * A petal family: [fill] for tiles, chips and avatars, [ink] for text and icons on it (≥ 4.5:1 in
 * both themes). Families mark things of the same kind; they never carry status.
 */
@Immutable
data class WakeMeFamily(val fill: Color, val ink: Color)

@Immutable
data class WakeMePalette(
${families.map(([k]) => `    val ${k}: WakeMeFamily,`).join('\n')}
) {
    /** Friend avatars, in the same order as iOS, so a friend has one colour on both. */
    val avatars: List<WakeMeFamily> get() = listOf(${tokens.palette.avatars.join(', ')})
}

object WakeMeTokens {
    val Light = WakeMeColors(
${kotlinPalette('light')}
    )
    val Dark = WakeMeColors(
${kotlinPalette('dark')}
    )
    /** Android 14+ contrast level at medium or high. */
    val LightHighContrast = WakeMeColors(
${kotlinPalette('lightHc')}
    )
    val DarkHighContrast = WakeMeColors(
${kotlinPalette('darkHc')}
    )
    val PaletteLight = WakeMePalette(
${kotlinFamilies('light')}
    )
    val PaletteDark = WakeMePalette(
${kotlinFamilies('dark')}
    )
${illustration.map(([k, v]) => `    val illustration${camel(k)} = ${kotlinColor(v)}`).join('\n')}
${Object.entries(tokens.spacing).map(([k, v]) => `    val space${camel(k)} = ${v}.dp`).join('\n')}
${Object.entries(tokens.radius).map(([k, v]) => `    val radius${camel(k)} = ${v}.dp`).join('\n')}
    val minTouchTarget = ${tokens.touchTarget.androidDp}.dp
    val alarmControl = ${tokens.touchTarget.alarmControlMin}.dp
    val controlHeight = ${tokens.touchTarget.controlHeight}.dp
${Object.entries(tokens.opacity).map(([k, v]) => `    const val opacity${camel(k)} = ${v}f`).join('\n')}
    val elevationOverlay = ${elevation.overlayAndroidDp}.dp
${Object.entries(tokens.motion.durationMs).map(([k, v]) => `    const val motion${camel(k)}Ms = ${v}`).join('\n')}
${springs.map(([k, s]) => `    const val spring${camel(k)}Damping = ${s.damping}f\n    const val spring${camel(k)}Stiffness = ${stiffness(s.response).toFixed(1)}f`).join('\n')}
    const val pressScale = ${tokens.motion.pressScale}f
    val screenMargin = ${layout.screenMarginAndroid}.dp
${numeric(tokens.layout).filter(([k]) => !k.startsWith('screenMargin')).map(([k, v]) => `    val layout${camel(k)} = ${v}.dp`).join('\n')}
${numeric(tokens.component).map(([k, v]) => `    val ${k} = ${v}.dp`).join('\n')}
    const val staggerMs = ${tokens.motion.staggerMs}
    const val staggerMaxItems = ${tokens.motion.staggerMaxItems}
}

/** Semantic type roles (sp, so they follow font scale). Figures are tabular in every role. */
object WakeMeType {
${typeRoles.map(([k, r]) => `    /** ${r.use} */\n    val ${k} = ${kotlinStyle(r)}`).join('\n')}
${typeRoles.filter(([, r]) => r.maxScale !== undefined).map(([k, r]) => `    /** ${k} grows with font scale up to ${r.maxScale}× and no further, so it can't overflow. */\n    const val ${k}MaxScale = ${r.maxScale}f`).join('\n')}
}
`;

const outputs: [string, string][] = [
  ['apps/ios/WakeMe/DesignSystem/Tokens.generated.swift', swift],
  ['apps/ios/WakeMe/DesignSystem/Icons.generated.swift', swiftIcons],
  ['apps/ios/WakeMe/Resources/Icons.xcassets/Contents.json', `${JSON.stringify({ info: { author: 'xcode', version: 1 } }, null, 2)}\n`],
  ['apps/android/app/src/main/java/app/wakeme/designsystem/Tokens.kt', kotlin],
  ['apps/android/app/src/main/java/app/wakeme/designsystem/Icons.kt', kotlinIcons],
  ['packages/design-tokens/contrast-report.md', report.join('\n') + '\n'],
  ...icons.flatMap((i): [string, string][] => [
    [`apps/android/app/src/main/res/drawable/${i.res}.xml`, androidVector(i)],
    [`apps/ios/WakeMe/Resources/Icons.xcassets/${i.res}.imageset/${i.res}.svg`, iosSvg(i)],
    [`apps/ios/WakeMe/Resources/Icons.xcassets/${i.res}.imageset/Contents.json`, iosImageSet(i)],
  ]),
];
const check = process.argv.includes('--check');
let stale = false;
for (const [rel, content] of outputs) {
  const target = fileURLToPath(new URL(rel, root));
  if (check) {
    let current = '';
    try {
      current = readFileSync(target, 'utf8');
    } catch {
      /* missing */
    }
    if (current !== content) stale = true;
  } else {
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content);
  }
}
console.log(report.join('\n'));
console.log(`\n${icons.length} icons.`);
if (failed) {
  console.error('\nContrast check failed.');
  process.exit(1);
}
if (check && stale) {
  console.error('Generated token files are stale. Run: npm run generate:tokens');
  process.exit(1);
}

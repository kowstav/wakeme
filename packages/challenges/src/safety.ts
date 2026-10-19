import type { ChallengeDefinition, SafetyCategory, SafetyFinding } from './types.ts';

// Deterministic safety rules applied to every challenge, whether written by a
// person or drafted by AI. They are intentionally conservative: a rejected
// challenge can be rephrased, while an unsafe wake condition can hurt someone.
// Rules are data so they can be reviewed and extended with adversarial evals.

interface Rule {
  category: SafetyCategory;
  pattern: RegExp;
  /**
   * Only apply to task-like text (checklists, timers, titles, fallback). Quiz
   * prompts and answers are trivia the user taps or types; the harms there are
   * markup, links, sexual content, credential phishing, money apps and abuse.
   */
  taskOnly?: boolean;
}

const NUM_WORDS = '(?:\\d+|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|several|multiple|many|a whole|an entire|entire|whole)';
const VESSELS = '(?:glass(?:es)?|cups?|mugs?|liters?|litres?|l\\b|ml\\b|bottles?|cans?|gallons?|pints?|shots?|jugs?|spoon(?:ful)?s?|tablespoons?|teaspoons?|slices?|pieces?|bowls?|plates?)';

const RULES: Rule[] = [
  // Executable content and links: challenges are inert data rendered natively.
  { category: 'code_or_markup', pattern: /<\s*\/?\s*(script|iframe|img|svg|style|object|embed|link|meta|a)\b/i },
  { category: 'code_or_markup', pattern: /\b(javascript|data|vbscript)\s*:/i },
  { category: 'code_or_markup', pattern: /\b(eval|exec|function|require|import)\s*\(/i },
  { category: 'code_or_markup', pattern: /```|\$\{|\{\{|\}\}|<%|%>/ },
  { category: 'link', pattern: /\bhttps?:\/\//i },
  { category: 'link', pattern: /\bwww\./i },
  { category: 'link', pattern: /\b[a-z0-9-]{2,}\.(com|net|org|io|app|ly|me|co|xyz|ru|cn|info|biz|gg|tk|link|click|top|site|online)\b/i },

  // Rapid or excessive consumption (e.g. "drink five glasses of water").
  {
    category: 'rapid_consumption',
    pattern: new RegExp(`\\b(chug|down|drink|eat|swallow|finish|gulp|consume)\\w*\\b[^.!?\\n]{0,40}\\b${NUM_WORDS}\\b[^.!?\\n]{0,20}\\b${VESSELS}`, 'i'),
    taskOnly: true,
  },
  {
    // The same instruction hidden at the start of a quiz sentence.
    category: 'rapid_consumption',
    pattern: new RegExp(`(^|[.!?:;]\\s*)(now\\s+|first\\s+|then\\s+)?(chug|drink|eat|swallow|gulp)\\b[^.!?\\n]{0,40}\\b${NUM_WORDS}\\b[^.!?\\n]{0,20}\\b${VESSELS}`, 'i'),
  },
  { category: 'rapid_consumption', pattern: /\b(drink|eat|chug|swallow|gulp)\w*\b.{0,40}\bas (fast|quickly|much) as\b/i, taskOnly: true },
  { category: 'rapid_consumption', pattern: /\bas (fast|quickly) as (you can|possible)\b.{0,40}\b(drink|eat|chug|swallow|gulp)/i, taskOnly: true },
  { category: 'rapid_consumption', pattern: /\b(chug\w*|gallon challenge|water challenge|eating contest|speed[- ]?eat\w*)\b/i, taskOnly: true },
  { category: 'rapid_consumption', pattern: /\b(cinnamon challenge|tide pods?|ghost peppers?|carolina reapers?|one chip challenge)\b/i },

  // Breath holding, choking and fainting.
  { category: 'breath_or_choking', pattern: /\bhold (your|ur|ya) breath\b/i },
  { category: 'breath_or_choking', pattern: /\b(chok(e|ing)|strangl\w*|suffocat\w*|blackout challenge)\b/i },
  { category: 'breath_or_choking', pattern: /\b(pass(ing)? out|faint(ing)?)\b/i, taskOnly: true },

  // Pain, injury, self-harm and dangerous temperature exposure.
  { category: 'pain_or_self_harm', pattern: /\b(slap|punch|hit|hurt|cut|burn|pinch|bite|whip|injure|harm|scratch)\w*\s+(yourself|urself|your (face|arm|leg|hand|body|skin|head))\b/i },
  { category: 'pain_or_self_harm', pattern: /\b(self[- ]?harm\w*|suicid\w*|kill (yourself|urself)|unalive)\b/i },
  { category: 'pain_or_self_harm', pattern: /\b(ice bath|cold plunge|ice water (on|over) your|freezing shower)\b/i, taskOnly: true },
  { category: 'pain_or_self_harm', pattern: /\b(until|till) (it hurts|you (cry|bleed|faint|pass out|throw up|vomit|puke))\b/i },
  { category: 'pain_or_self_harm', pattern: /\bpain(ful)?\b/i, taskOnly: true },

  // Substances: any task involving them, and imperative consumption anywhere.
  {
    // Imperative at the start of a sentence, so quiz trivia ("Where did we drink wine?") passes.
    category: 'substances',
    pattern: /(^|[.!?:;]\s*)(now\s+|first\s+|then\s+)?(drink|take|have|smoke|vape|pop|snort|down)\s+(a |an |some |\d+ |two |three )?(shots?|beers?|alcohol|vodka|tequila|whisk(e)?y|wine|pills?|weed|joints?|edibles?|energy drinks?|caffeine pills?|cigarettes?)\b/i,
  },
  {
    category: 'substances',
    pattern: /\b(alcohol|beer|vodka|whisk(e)?y|tequila|rum|wine|shots?|drunk|vape|vaping|cigarettes?|smoke|weed|cannabis|marijuana|edibles?|pills?|caffeine|energy drinks?|adderall|xanax|cocaine|drugs?|nicotine)\b/i,
    taskOnly: true,
  },

  // Dangerous physical demands (numeric bounds are checked separately below).
  { category: 'dangerous_physical', pattern: /\b(until|till) (failure|exhaustion|you drop|you collapse)\b/i, taskOnly: true },
  { category: 'dangerous_physical', pattern: /\b(roof|balcony|ledge|window ?sill|jump off|climb (onto|on|up))\b/i, taskOnly: true },
  { category: 'dangerous_physical', pattern: /\b(sprint|run|jog)\w*\b.{0,30}\b(outside|street|road|traffic|in the dark|at night)\b/i, taskOnly: true },
  { category: 'dangerous_physical', pattern: /\b(in the dark|with (your )?eyes (closed|shut))\b.{0,30}\b(walk|run|stairs|jump)/i, taskOnly: true },

  // Sexual content and nudity.
  { category: 'sexual', pattern: /\b(naked|nude|nudes|strip(ping|tease)?|undress\w*|sexy|sexual|sex|porn\w*|onlyfans|lingerie|underwear|boobs?|genitals?|horny|nsfw)\b/i },

  // Humiliation and coercing contact with other people.
  { category: 'humiliation_or_coercion', pattern: /\b(humiliat\w*|degrad\w*|embarrass\w* (photo|video|picture|selfie|yourself))\b/i },
  { category: 'humiliation_or_coercion', pattern: /\bpost\w*\b.{0,20}\b(publicly|online|on (instagram|insta|tiktok|twitter|x|facebook|snapchat|snap|reddit|whatsapp status))\b/i, taskOnly: true },
  {
    category: 'humiliation_or_coercion',
    pattern: /\b(call|text|message|dm|email|ring|phone)\w*\s+(your |ur )?(ex|boss|teacher|parents?|mom|mum|dad|crush|professor|manager|landlord|police)\b/i,
    taskOnly: true,
  },
  { category: 'humiliation_or_coercion', pattern: /\b(prank call|confess\w* to|send (a )?(selfie|photo|picture) to)\b/i, taskOnly: true },

  // Credentials and identity data (phishing risk even inside a quiz).
  {
    category: 'credentials',
    pattern: /\b(password|passcode|pass code|otp|one[- ]time (code|password)|2fa|two[- ]factor|verification code|security code|cvv|cvc|card number|bank (details|account)|account number|routing number|sort code|iban|social security|ssn|aadhaar|pan card|passport number|login details|seed phrase|recovery phrase|private key)\b/i,
  },
  { category: 'credentials', pattern: /\b(your|ur|the) pin\b|\bpin (code|number)\b/i },

  // Money.
  { category: 'financial', pattern: /\b(venmo|paypal|zelle|cash ?app|bitcoin|btc|ethereum|crypto\w*|gift ?cards?|wire transfer|western union|paytm|gpay|google pay|apple pay|phonepe)\b/i },
  { category: 'financial', pattern: /\b(send|transfer|pay|give|lend|owe)\w*\b.{0,30}(money|\$|£|€|₹|\bdollars?\b|\brupees?\b|\beuros?\b|\bpounds?\b|\bbucks\b|\bcash\b)/i, taskOnly: true },

  // Location exposure.
  { category: 'location_exposure', pattern: /\b(share|send|post|reveal)\w*\b.{0,25}\b(your |ur |the )?(location|address|gps|coordinates|whereabouts)\b/i },
  { category: 'location_exposure', pattern: /\b(photo|picture|pic|selfie) of (your |ur |the )?(street|house|building|front door|address|room number)\b/i },

  // Distracted driving.
  { category: 'driving', pattern: /\b(while|when) (you('| a)re )?driv\w*\b/i, taskOnly: true },
  { category: 'driving', pattern: /\bdriv\w*\b.{0,30}\b(phone|scan|type|text|solve|quiz)\b/i, taskOnly: true },
];

const REP_PATTERN = /\b(\d{1,4})\s*(x\s*)?(push[- ]?ups?|press[- ]?ups?|sit[- ]?ups?|squats?|burpees?|jumping jacks?|laps?|reps?|crunches|lunges|star jumps|pull[- ]?ups?|flights? of stairs)\b/gi;
const DISTANCE_PATTERN = /\b(\d+(?:\.\d+)?)\s*(miles?|mi\b|km\b|kilomet(?:er|re)s?)\b/gi;
const DURATION_PATTERN = /\b(\d{1,4})\s*(minutes?|mins?|hours?|hrs?)\b.{0,30}\b(plank|run|jog|cold shower|wall sit|hold)\b/gi;
const DURATION_AFTER_PATTERN = /\b(plank|run|jog|cold shower|wall sit|hold)\w*\b.{0,30}?\b(\d{1,4})\s*(minutes?|mins?|hours?|hrs?)\b/gi;

export const PHYSICAL_BOUNDS = { maxReps: 30, maxDistanceKm: 3, maxStrenuousMinutes: 5 } as const;

const MESSAGES: Record<SafetyCategory, string> = {
  code_or_markup: 'Challenges are plain text. Code, markup and templates are not supported.',
  link: 'Links are not allowed in challenges.',
  rapid_consumption: 'Challenges cannot ask for fast or large amounts of eating or drinking. Try a gentle reminder instead.',
  breath_or_choking: 'Challenges cannot involve holding breath, choking or fainting.',
  pain_or_self_harm: 'Challenges cannot involve pain, injury or harsh cold exposure.',
  substances: 'Challenges cannot involve alcohol, drugs, nicotine or caffeine.',
  dangerous_physical: 'This asks for more physical effort or risk than wake challenges allow. Keep it short and safe, with a seated option.',
  sexual: 'Sexual content and nudity are not allowed.',
  humiliation_or_coercion: 'Challenges cannot pressure someone to post publicly, embarrass themselves or contact other people.',
  credentials: 'Challenges cannot ask for passwords, codes or identity details.',
  financial: 'Challenges cannot involve money or payments.',
  location_exposure: 'Challenges cannot ask anyone to reveal where they are.',
  driving: 'Challenges must never be done while driving.',
};

export function safetyMessage(category: SafetyCategory): string {
  return MESSAGES[category];
}

interface TextField {
  path: string;
  text: string;
  task: boolean;
}

function collectText(def: ChallengeDefinition): TextField[] {
  const out: TextField[] = [{ path: '/title', text: def.title, task: true }];
  if (def.fallback?.prompt) out.push({ path: '/fallback/prompt', text: def.fallback.prompt, task: true });
  def.steps.forEach((s, i) => {
    const base = `/steps/${i}`;
    switch (s.type) {
      case 'multiple_choice':
        out.push({ path: `${base}/prompt`, text: s.prompt, task: false });
        s.options.forEach((o, j) => out.push({ path: `${base}/options/${j}`, text: o, task: false }));
        break;
      case 'short_answer':
        out.push({ path: `${base}/prompt`, text: s.prompt, task: false });
        s.acceptedAnswers.forEach((a, j) => out.push({ path: `${base}/acceptedAnswers/${j}`, text: a, task: false }));
        break;
      case 'memory_order':
        out.push({ path: `${base}/prompt`, text: s.prompt, task: false });
        s.items.forEach((a, j) => out.push({ path: `${base}/items/${j}`, text: a, task: false }));
        break;
      case 'chess':
        out.push({ path: `${base}/prompt`, text: s.prompt, task: false });
        break;
      case 'timer':
      case 'qr_code':
        out.push({ path: `${base}/prompt`, text: s.prompt, task: true });
        break;
      case 'self_attested':
        out.push({ path: `${base}/prompt`, text: s.prompt, task: true });
        s.items.forEach((a, j) => out.push({ path: `${base}/items/${j}`, text: a, task: true }));
        if (s.alternative) out.push({ path: `${base}/alternative`, text: s.alternative, task: true });
        break;
      case 'math':
        break;
    }
  });
  return out;
}

/** Scan free text with the same rules (also used for AI prompts and creator-supplied facts). */
export function scanText(text: string, path: string, task: boolean): SafetyFinding[] {
  const findings: SafetyFinding[] = [];
  const seen = new Set<SafetyCategory>();
  const normalized = text.normalize('NFKC');
  for (const rule of RULES) {
    if (rule.taskOnly && !task) continue;
    if (seen.has(rule.category)) continue;
    if (rule.pattern.test(normalized)) {
      seen.add(rule.category);
      findings.push({ category: rule.category, path, message: MESSAGES[rule.category] });
    }
  }
  if (task && !seen.has('dangerous_physical')) {
    let excessive = false;
    for (const m of normalized.matchAll(REP_PATTERN)) if (Number(m[1]) > PHYSICAL_BOUNDS.maxReps) excessive = true;
    for (const m of normalized.matchAll(DISTANCE_PATTERN)) {
      const unit = m[2]!.toLowerCase();
      const km = Number(m[1]) * (unit.startsWith('mi') ? 1.609 : 1);
      if (km > PHYSICAL_BOUNDS.maxDistanceKm) excessive = true;
    }
    for (const m of normalized.matchAll(DURATION_PATTERN)) {
      const minutes = Number(m[1]) * (m[2]!.toLowerCase().startsWith('h') ? 60 : 1);
      if (minutes > PHYSICAL_BOUNDS.maxStrenuousMinutes) excessive = true;
    }
    for (const m of normalized.matchAll(DURATION_AFTER_PATTERN)) {
      const minutes = Number(m[2]) * (m[3]!.toLowerCase().startsWith('h') ? 60 : 1);
      if (minutes > PHYSICAL_BOUNDS.maxStrenuousMinutes) excessive = true;
    }
    if (excessive) findings.push({ category: 'dangerous_physical', path, message: MESSAGES.dangerous_physical });
  }
  return findings;
}

export function scanChallenge(def: ChallengeDefinition): SafetyFinding[] {
  return collectText(def).flatMap((f) => scanText(f.text, f.path, f.task));
}

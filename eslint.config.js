const { FlatCompat } = require("@eslint/eslintrc");
const js = require("@eslint/js");
const modernNodePlugin = require("eslint-plugin-n");

const compat = new FlatCompat({
  baseDirectory: __dirname,
  recommendedConfig: js.configs.recommended,
  allConfig: js.configs.all,
});
const universeConfigs = compat.extends("universe/native", "universe/web");
const translatedNodeRules = {};
const legacyNodeRuleNames = new Set();
for (const config of universeConfigs) {
  for (const [ruleName, setting] of Object.entries(config.rules ?? {})) {
    if (!ruleName.startsWith("node/")) continue;
    legacyNodeRuleNames.add(ruleName);
    const shortName = ruleName.slice("node/".length);
    if (modernNodePlugin.rules[shortName]) {
      translatedNodeRules[`n/${shortName}`] = setting;
    }
  }
}

module.exports = [
  {
    ignores: ["build/**", "plugin/build/**", "example/**"],
  },
  ...universeConfigs,
  {
    // eslint-plugin-node uses the pre-ESLint-9 context API. Preserve Universe's
    // rule settings through the maintained eslint-plugin-n equivalents.
    plugins: { n: modernNodePlugin },
    rules: {
      ...Object.fromEntries(
        [...legacyNodeRuleNames].map((ruleName) => [ruleName, "off"]),
      ),
      ...translatedNodeRules,
    },
  },
  {
    rules: {
      "prettier/prettier": ["warn", { endOfLine: "auto" }],
    },
  },
];

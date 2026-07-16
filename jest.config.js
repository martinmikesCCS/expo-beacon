module.exports = {
  ...require("expo-module-scripts/jest-preset-plugin"),
  testPathIgnorePatterns: ["/example/"],
  transform: {
    "^.+\\.[jt]sx?$": [
      "babel-jest",
      { configFile: require.resolve("./babel.config.cjs") },
    ],
  },
};

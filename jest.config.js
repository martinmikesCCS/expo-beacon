module.exports = {
  ...require("expo-module-scripts/jest-preset-plugin"),
  testPathIgnorePatterns: ["/example/"],
  moduleNameMapper: {
    "^(\\.{1,2}/.*)\\.js$": "$1",
  },
  transform: {
    "^.+\\.[jt]sx?$": [
      "babel-jest",
      { configFile: require.resolve("./babel.config.cjs") },
    ],
  },
};

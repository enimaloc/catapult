import { defineConfig } from "vitest/config";

export default defineConfig({
    test: {
        environment: "jsdom",
        include: ["src/test/js/**/*.test.js"],
        setupFiles: ["src/test/js/setup.js"],
        restoreMocks: true,
        coverage: {
            provider: "v8",
            include: ["src/main/resources/static/js/**/*.js"],
            reporter: ["text-summary", "html", "lcov"],
            reportsDirectory: "build/reports/js-coverage",
            thresholds: { lines: 95 },
        },
    },
});

import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{js,ts,jsx,tsx,mdx}",
    "./components/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        brand: {
          50: "#f3f8ff",
          100: "#e5efff",
          200: "#c7dcff",
          300: "#9bc2ff",
          400: "#679eff",
          500: "#3978f6",
          600: "#245de5",
          700: "#1f4bc2",
          800: "#203f99",
          900: "#20386f",
          950: "#142346",
        },
      },
    },
  },
  plugins: [],
};

export default config;

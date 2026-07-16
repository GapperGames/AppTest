import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.openingreflex.app",
  appName: "Opening Reflex",
  webDir: "dist",
  android: {
    // Allow the WebView to load the local WASM engine over the bundled assets.
    allowMixedContent: false,
  },
};

export default config;

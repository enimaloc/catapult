/**
 * Global bootstrap, run once per full page load: the mdui theme.
 * (Web Components themselves come from mdui.global.js, loaded in <head>.)
 */

const savedTheme = localStorage.getItem("catapult-theme");

// mdui 2 reads its theme from a `mdui-theme-*` class on <html> (an `mdui-theme`
// attribute is ignored), and derives every --mdui-color-* token from one seed
// color — Catapult's primary, so mdui components share the site's palette.
mdui.setTheme(savedTheme === "light" ? "light" : "dark");
mdui.setColorScheme("#8b5cf6");

/**
 * Catapult frontend.
 *
 * This file intentionally contains only application logic.
 * Web Components provided by mdui are loaded separately.
 */

/*
 * Theme
 */

const savedTheme = localStorage.getItem("catapult-theme");

// mdui 2 reads its theme from a `mdui-theme-*` class on <html> (an `mdui-theme`
// attribute is ignored), and derives every --mdui-color-* token from one seed
// color — Catapult's primary, so mdui components share the site's palette.
mdui.setTheme(savedTheme === "light" ? "light" : "dark");
mdui.setColorScheme("#8b5cf6");


/*
 * Smooth navigation
 */

document
    .querySelectorAll('a[href^="#"]')
    .forEach(link => {

        link.addEventListener("click", event => {

            const targetId =
                link.getAttribute("href");

            const target =
                document.querySelector(targetId);

            if (!target) {
                return;
            }

            event.preventDefault();

            target.scrollIntoView({
                behavior: "smooth",
                block: "start"
            });

        });

    });
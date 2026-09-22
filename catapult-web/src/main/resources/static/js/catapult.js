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

if (savedTheme === "light" || savedTheme === "dark") {
    document.documentElement.setAttribute(
        "mdui-theme",
        savedTheme
    );
} else {
    document.documentElement.setAttribute(
        "mdui-theme",
        "dark"
    );
}


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
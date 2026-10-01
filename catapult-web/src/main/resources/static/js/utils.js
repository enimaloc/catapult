document.addEventListener("catapult:render", () => {
    document.querySelectorAll('mdui-collapse-item').forEach(item => {
        item.addEventListener('open', () => {
            item.classList.add('is-open');
        });

        item.addEventListener('close', () => {
            item.classList.remove('is-open');
        });
    });
})
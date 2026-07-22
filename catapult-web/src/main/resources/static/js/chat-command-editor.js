(function () {
    let currentCmd = null;

    function openEditor(cmd) {
        currentCmd = cmd;
        document.getElementById('ceName').value = cmd.name;
        const isBuiltin = !!(cmd.presetKey && cmd.presetKey.indexOf('builtin:') === 0);
        document.getElementById('ceName').disabled = isBuiltin;
        document.getElementById('ceTextArea').value = cmd.text || cmd.template || '';
        document.getElementById('ceTraceOutput').hidden = true;
        document.getElementById('chatCommandEditorModal').hidden = false;
        document.getElementById('chatCommandEditorModal').style.display = 'flex';
        selectTab('blocks');
    }

    function closeEditor() {
        document.getElementById('chatCommandEditorModal').hidden = true;
        document.getElementById('chatCommandEditorModal').style.display = 'none';
        currentCmd = null;
    }

    function selectTab(tab) {
        document.querySelectorAll('.ce-tab-btn').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.tab === tab));
        document.getElementById('ceBlocksPane').hidden = tab !== 'blocks';
        document.getElementById('ceTextPane').hidden = tab !== 'text';
        document.getElementById('ceJsPane').hidden = tab !== 'js';
    }

    document.querySelectorAll('.ce-tab-btn').forEach(btn => {
        btn.onclick = () => selectTab(btn.dataset.tab);
    });

    document.getElementById('ceClose').onclick = closeEditor;

    document.getElementById('ceSaveBtn').onclick = async () => {
        if (!currentCmd) return;
        try {
            await window.catapultWs.request('chat-commands.update', {
                id: currentCmd.id,
                name: document.getElementById('ceName').value,
                template: document.getElementById('ceTextArea').value,
                permission: currentCmd.permission,
                enabled: currentCmd.enabled,
                fallbacks: {}
            });
            closeEditor();
            if (window.chatCommandEditor.onSaved) window.chatCommandEditor.onSaved();
        } catch (err) {
            if (window.catapultOverlay && window.catapultOverlay.showToast) {
                window.catapultOverlay.showToast(String(err), { kind: 'error', duration: 6000 });
            }
        }
    };

    document.getElementById('ceTestBtn').onclick = async () => {
        if (!currentCmd) return;
        try {
            const resp = await window.catapultWs.request('chat-commands.test', {
                id: currentCmd.id,
                overrides: {}
            });
            renderTrace(resp.result);
        } catch (err) {
            if (window.catapultOverlay && window.catapultOverlay.showToast) {
                window.catapultOverlay.showToast(String(err), { kind: 'error', duration: 6000 });
            }
        }
    };

    function renderTrace(result) {
        const el = document.getElementById('ceTraceOutput');
        el.hidden = false;
        el.replaceChildren();
        for (const entry of (result.trace || [])) {
            const line = document.createElement('div');
            line.className = entry.error ? 'trace-entry trace-error' : 'trace-entry';
            line.textContent = '[' + entry.nodeType + '] ' + entry.description + ' -> ' + entry.resolvedValue;
            el.appendChild(line);
        }
        const output = document.createElement('div');
        output.className = 'trace-final-output';
        output.textContent = result.output;
        el.appendChild(output);
    }

    window.chatCommandEditor = window.chatCommandEditor || {};
    window.chatCommandEditor.openEditor = openEditor;
})();

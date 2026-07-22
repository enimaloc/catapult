(function () {
    let currentCmd = null;
    let currentTab = 'blocks';
    let workspace = null;

    // ---- Blockly custom blocks (mirror the 5 CommandNode types) ----

    Blockly.defineBlocksWithJsonArray([
        {
            "type": "cmd_literal",
            "message0": "texte %1",
            "args0": [{ "type": "field_input", "name": "TEXT", "text": "" }],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 60
        },
        {
            "type": "cmd_placeholder",
            "message0": "placeholder %1",
            "args0": [{ "type": "field_input", "name": "PATH", "text": "game#name" }],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 200
        },
        {
            "type": "cmd_service_call",
            "message0": "appel %1 . %2 ( %3 )",
            "args0": [
                { "type": "field_input", "name": "NAMESPACE", "text": "igdb" },
                { "type": "field_input", "name": "FUNCTION", "text": "getGame" },
                { "type": "field_input", "name": "ARG", "text": "" }
            ],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 290
        },
        {
            "type": "cmd_if",
            "message0": "si %1 == %2 alors %3 sinon %4",
            "args0": [
                { "type": "field_input", "name": "LEFT", "text": "" },
                { "type": "field_input", "name": "RIGHT", "text": "" },
                { "type": "input_statement", "name": "THEN" },
                { "type": "input_statement", "name": "ELSE" }
            ],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 20
        },
        {
            "type": "cmd_for_each",
            "message0": "pour chaque %1 dans %2 %3",
            "args0": [
                { "type": "field_input", "name": "BINDING", "text": "f" },
                { "type": "field_input", "name": "LIST_SOURCE", "text": "fallbacks" },
                { "type": "input_statement", "name": "BODY" }
            ],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 120
        }
    ]);

    function ensureWorkspace() {
        if (workspace) return workspace;
        workspace = Blockly.inject('ceBlocksPane', {
            toolbox: {
                kind: "flyoutToolbox",
                contents: [
                    { kind: "block", type: "cmd_literal" },
                    { kind: "block", type: "cmd_placeholder" },
                    { kind: "block", type: "cmd_service_call" },
                    { kind: "block", type: "cmd_if" },
                    { kind: "block", type: "cmd_for_each" }
                ]
            }
        });
        return workspace;
    }

    function blockToNode(block) {
        switch (block.type) {
            case 'cmd_literal':
                return { type: 'literal', text: block.getFieldValue('TEXT') };
            case 'cmd_placeholder':
                return { type: 'placeholder', path: block.getFieldValue('PATH') };
            case 'cmd_service_call':
                return {
                    type: 'service-call',
                    namespace: block.getFieldValue('NAMESPACE'),
                    function: block.getFieldValue('FUNCTION'),
                    args: [{ type: 'placeholder', path: block.getFieldValue('ARG') }]
                };
            case 'cmd_if':
                return {
                    type: 'if',
                    left: { type: 'placeholder', path: block.getFieldValue('LEFT') },
                    operator: '==',
                    right: { type: 'literal', text: block.getFieldValue('RIGHT') },
                    then: statementsToNodes(block.getInputTargetBlock('THEN')),
                    else: statementsToNodes(block.getInputTargetBlock('ELSE'))
                };
            case 'cmd_for_each':
                return {
                    type: 'for-each',
                    bindingName: block.getFieldValue('BINDING'),
                    listSource: block.getFieldValue('LIST_SOURCE'),
                    body: statementsToNodes(block.getInputTargetBlock('BODY'))
                };
            default:
                throw new Error('Unknown block type: ' + block.type);
        }
    }

    function statementsToNodes(firstBlock) {
        const nodes = [];
        let block = firstBlock;
        while (block) {
            nodes.push(blockToNode(block));
            block = block.getNextBlock();
        }
        return nodes;
    }

    function blocksToAst() {
        const top = ensureWorkspace().getTopBlocks(true);
        return { nodes: top.map(blockToNode) };
    }

    function nodeToBlock(ws, node) {
        let block;
        switch (node.type) {
            case 'literal':
                block = ws.newBlock('cmd_literal');
                block.setFieldValue(node.text, 'TEXT');
                break;
            case 'placeholder':
                block = ws.newBlock('cmd_placeholder');
                block.setFieldValue(node.path, 'PATH');
                break;
            case 'service-call':
                block = ws.newBlock('cmd_service_call');
                block.setFieldValue(node.namespace, 'NAMESPACE');
                block.setFieldValue(node.function, 'FUNCTION');
                block.setFieldValue((node.args[0] && node.args[0].path) || '', 'ARG');
                break;
            default:
                // Phase 1: if/for-each round-trip through the Text tab only — reconstructing
                // their nested statement inputs in the Blocks tab is a follow-up once the
                // literal/placeholder/service-call round-trip is verified working end-to-end.
                throw new Error('Unsupported node type for blocks view in Phase 1: ' + node.type);
        }
        block.initSvg();
        block.render();
        return block;
    }

    function astToBlocks(ast) {
        const ws = ensureWorkspace();
        ws.clear();
        let previous = null;
        for (const node of (ast.nodes || [])) {
            const block = nodeToBlock(ws, node);
            if (previous) previous.nextConnection.connect(block.previousConnection);
            previous = block;
        }
    }

    // ---- Text <-> AST bridging (grammar lives server-side only) ----

    async function textToAst(text) {
        const resp = await window.catapultWs.request('chat-commands.dsl.text-to-ast', { text: text });
        return JSON.parse(resp.result.ast);
    }

    async function astToText(ast) {
        const resp = await window.catapultWs.request('chat-commands.dsl.ast-to-text', { ast: JSON.stringify(ast) });
        return resp.result.text;
    }

    function reportEditorError(err) {
        const text = err && err.message ? err.message : String(err);
        if (window.catapultOverlay && window.catapultOverlay.showToast) {
            window.catapultOverlay.showToast(text, { kind: 'error', duration: 6000 });
        }
    }

    // ---- Modal wiring ----

    async function openEditor(cmd) {
        currentCmd = cmd;
        const isBuiltin = !!(cmd.presetKey && cmd.presetKey.indexOf('builtin:') === 0);
        document.getElementById('ceName').value = cmd.name;
        document.getElementById('ceName').disabled = isBuiltin;
        document.getElementById('ceTextArea').value = cmd.template || '';
        document.getElementById('ceTraceOutput').hidden = true;
        document.getElementById('chatCommandEditorModal').hidden = false;
        document.getElementById('chatCommandEditorModal').style.display = 'flex';
        currentTab = 'blocks';
        selectTabUI('blocks');
        try {
            astToBlocks(await textToAst(cmd.template || ''));
        } catch (err) {
            reportEditorError(err);
        }
    }

    function closeEditor() {
        document.getElementById('chatCommandEditorModal').hidden = true;
        document.getElementById('chatCommandEditorModal').style.display = 'none';
        currentCmd = null;
    }

    function selectTabUI(tab) {
        document.querySelectorAll('.ce-tab-btn').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.tab === tab));
        document.getElementById('ceBlocksPane').hidden = tab !== 'blocks';
        document.getElementById('ceTextPane').hidden = tab !== 'text';
        document.getElementById('ceJsPane').hidden = tab !== 'js';
        if (workspace) workspace.resize();
    }

    async function selectTab(tab) {
        if (tab === currentTab) return;
        try {
            if (currentTab === 'blocks' && tab === 'text') {
                document.getElementById('ceTextArea').value = await astToText(blocksToAst());
            } else if (currentTab === 'text' && tab === 'blocks') {
                astToBlocks(await textToAst(document.getElementById('ceTextArea').value));
            }
        } catch (err) {
            reportEditorError(err);
            return;
        }
        currentTab = tab;
        selectTabUI(tab);
    }

    document.querySelectorAll('.ce-tab-btn').forEach(btn => {
        btn.onclick = () => selectTab(btn.dataset.tab);
    });

    document.getElementById('ceClose').onclick = closeEditor;

    /**
     * The Blocks/Text tabs are kept in sync on every switch (see selectTab), so whichever
     * tab is active when Save is clicked already holds the authoritative content — falls
     * back to converting the Blocks tab if the user is looking at the (read-only, Phase 1)
     * JS tab when they save.
     */
    async function currentAst() {
        if (currentTab === 'text') {
            return textToAst(document.getElementById('ceTextArea').value);
        }
        return blocksToAst();
    }

    document.getElementById('ceSaveBtn').onclick = async () => {
        if (!currentCmd) return;
        try {
            const ast = await currentAst();
            await window.catapultWs.request('chat-commands.update', {
                id: currentCmd.id,
                name: document.getElementById('ceName').value,
                template: document.getElementById('ceTextArea').value,
                permission: currentCmd.permission,
                enabled: currentCmd.enabled,
                fallbacks: {},
                ast: JSON.stringify(ast)
            });
            closeEditor();
            if (window.chatCommandEditor.onSaved) window.chatCommandEditor.onSaved();
        } catch (err) {
            reportEditorError(err);
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
            reportEditorError(err);
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
    window.chatCommandEditor.blocksToAst = blocksToAst;
    window.chatCommandEditor.astToBlocks = astToBlocks;
})();

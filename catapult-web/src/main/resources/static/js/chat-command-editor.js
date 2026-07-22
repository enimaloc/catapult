(function () {
    // The Commands tab is loaded dynamically and its <script> tags are re-executed by
    // channel-tabs.js's executeScripts(), which recreates each <script src="..."> without
    // waiting for the previous one's network fetch to finish — so this file's own <script>
    // tag can finish loading (it's tiny) and start running before the much larger
    // blockly_compressed.js has actually loaded, leaving `Blockly` undefined. Poll for it
    // instead of relying on script tag document order.
    function whenBlocklyReady(callback) {
        if (window.Blockly) {
            callback();
            return;
        }
        setTimeout(function () { whenBlocklyReady(callback); }, 20);
    }

    whenBlocklyReady(function () {
    let currentCmd = null;
    let currentTab = 'blocks';
    let workspace = null;

    // ---- Theme: follow the site's current palette (app.js's setTheme sets/removes
    // [data-theme] on <html> and reads/writes CSS custom properties) ----

    function buildBlocklyTheme() {
        const style = getComputedStyle(document.documentElement);
        const cssVar = (name, fallback) => (style.getPropertyValue(name) || '').trim() || fallback;
        const themeName = 'catapult-' + (document.documentElement.getAttribute('data-theme') || 'dark');
        return Blockly.Theme.defineTheme(themeName, {
            base: Blockly.Themes.Classic,
            componentStyles: {
                workspaceBackgroundColour: cssVar('--bg-base', '#0f1117'),
                toolboxBackgroundColour: cssVar('--bg-surface', '#1a1d27'),
                toolboxForegroundColour: cssVar('--text', '#e2e8f0'),
                flyoutBackgroundColour: cssVar('--bg-elevated', '#13151f'),
                flyoutForegroundColour: cssVar('--text', '#e2e8f0'),
                flyoutOpacity: 1,
                scrollbarColour: cssVar('--border', '#2d2f3e'),
                insertionMarkerColour: cssVar('--text-bright', '#f1f5f9'),
                insertionMarkerOpacity: 0.3,
                markerColour: cssVar('--text-bright', '#f1f5f9'),
                cursorColour: cssVar('--text-bright', '#f1f5f9')
            }
        });
    }

    document.addEventListener('catapult:theme-changed', function () {
        if (workspace) workspace.setTheme(buildBlocklyTheme());
    });

    // ---- Blockly custom blocks (mirror the Statement/Expression node types) ----

    // ContextGetExpr: a single generic "get context" block with a dropdown of known
    // placeholder paths, per docs/specs/2026-07-21-chat-command-block-dsl-design.md#block-editor.
    var KNOWN_CONTEXT_PATHS = [
        ["game#name", "game#name"],
        ["game#summary", "game#summary"],
        ["game#release_date", "game#release_date"],
        ["game#store#url", "game#store#url"],
        ["game#store#steam", "game#store#steam"],
        ["game#store#xbox", "game#store#xbox"],
        ["game#store#battlenet", "game#store#battlenet"],
        ["game#store#official", "game#store#official"],
        ["game#igdb#url", "game#igdb#url"],
        ["game#agerating", "game#agerating"],
        ["tw#active", "tw#active"]
    ];

    Blockly.defineBlocksWithJsonArray([
        // ---- value blocks (Expression) ----
        {
            "type": "cmd_literal_string",
            "message0": "\" %1 \"",
            "args0": [{ "type": "field_input", "name": "VALUE", "text": "" }],
            "output": null,
            "colour": 60
        },
        {
            "type": "cmd_literal_number",
            "message0": "# %1",
            "args0": [{ "type": "field_number", "name": "VALUE", "value": 0 }],
            "output": null,
            "colour": 65
        },
        {
            "type": "cmd_literal_boolean",
            "message0": "%1",
            "args0": [{ "type": "field_dropdown", "name": "VALUE", "options": [["true", "true"], ["false", "false"]] }],
            "output": null,
            "colour": 70
        },
        {
            "type": "cmd_var_ref",
            "message0": "var %1",
            "args0": [{ "type": "field_input", "name": "NAME", "text": "msg" }],
            "output": null,
            "colour": 150
        },
        {
            "type": "cmd_context_get",
            "message0": "get %1",
            "args0": [{ "type": "field_dropdown", "name": "PATH", "options": KNOWN_CONTEXT_PATHS }],
            "output": null,
            "colour": 200
        },
        // ServiceCallExpr: one dedicated block per registered function (Phase 1's fixed 3).
        {
            "type": "cmd_call_igdb_get_game",
            "message0": "igdb.getGame( %1 )",
            "args0": [{ "type": "input_value", "name": "QUERY" }],
            "output": null,
            "colour": 290
        },
        {
            "type": "cmd_call_twitch_get_user",
            "message0": "twitch.getUser()",
            "args0": [],
            "output": null,
            "colour": 290
        },
        {
            "type": "cmd_call_steam_get_price",
            "message0": "steam.getPrice( %1 )",
            "args0": [{ "type": "input_value", "name": "APP_ID" }],
            "output": null,
            "colour": 290
        },
        // ---- statement blocks ----
        {
            "type": "cmd_var_decl",
            "message0": "var %1 = %2",
            "args0": [
                { "type": "field_input", "name": "NAME", "text": "msg" },
                { "type": "input_value", "name": "INIT" }
            ],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 20
        },
        {
            "type": "cmd_assign",
            "message0": "%1 = %2",
            "args0": [
                { "type": "field_input", "name": "NAME", "text": "msg" },
                { "type": "input_value", "name": "EXPR" }
            ],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 20
        },
        {
            "type": "cmd_concat",
            "message0": "%1 += %2",
            "args0": [
                { "type": "field_input", "name": "NAME", "text": "msg" },
                { "type": "input_value", "name": "EXPR" }
            ],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 20
        },
        {
            "type": "cmd_print",
            "message0": "print %1",
            "args0": [{ "type": "input_value", "name": "EXPR" }],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 40
        },
        {
            "type": "cmd_if",
            "message0": "if %1 %2 %3 then %4 else %5",
            "args0": [
                { "type": "input_value", "name": "LEFT" },
                { "type": "field_dropdown", "name": "OPERATOR",
                    "options": [["==", "=="], ["!=", "!="], ["<", "<"], [">", ">"], ["<=", "<="], [">=", ">="]] },
                { "type": "input_value", "name": "RIGHT" },
                { "type": "input_statement", "name": "THEN" },
                { "type": "input_statement", "name": "ELSE" }
            ],
            "previousStatement": null,
            "nextStatement": null,
            "colour": 210
        },
        {
            "type": "cmd_for_each",
            "message0": "for each %1 in %2 %3",
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
                kind: "categoryToolbox",
                contents: [
                    {
                        kind: "category", name: "Variables", colour: "20",
                        contents: [
                            { kind: "block", type: "cmd_var_decl" },
                            { kind: "block", type: "cmd_assign" },
                            { kind: "block", type: "cmd_concat" },
                            { kind: "block", type: "cmd_var_ref" }
                        ]
                    },
                    {
                        kind: "category", name: "Contrôle", colour: "210",
                        contents: [
                            { kind: "block", type: "cmd_if" },
                            { kind: "block", type: "cmd_for_each" }
                        ]
                    },
                    {
                        kind: "category", name: "Texte", colour: "60",
                        contents: [
                            { kind: "block", type: "cmd_print" },
                            { kind: "block", type: "cmd_literal_string" },
                            { kind: "block", type: "cmd_literal_number" },
                            { kind: "block", type: "cmd_literal_boolean" }
                        ]
                    },
                    {
                        kind: "category", name: "Contexte", colour: "200",
                        contents: [
                            { kind: "block", type: "cmd_context_get" }
                        ]
                    },
                    {
                        kind: "category", name: "Fonctions", colour: "290",
                        contents: [
                            { kind: "block", type: "cmd_call_igdb_get_game" },
                            { kind: "block", type: "cmd_call_twitch_get_user" },
                            { kind: "block", type: "cmd_call_steam_get_price" }
                        ]
                    }
                ]
            },
            theme: buildBlocklyTheme()
        });
        workspace.resize();
        return workspace;
    }

    // ---- Blocks -> AST ----

    function exprBlockToNode(block) {
        if (!block) return { type: 'literal', value: '', valueType: 'STRING' };
        switch (block.type) {
            case 'cmd_literal_string':
                return { type: 'literal', value: block.getFieldValue('VALUE'), valueType: 'STRING' };
            case 'cmd_literal_number':
                return { type: 'literal', value: String(block.getFieldValue('VALUE')), valueType: 'NUMBER' };
            case 'cmd_literal_boolean':
                return { type: 'literal', value: block.getFieldValue('VALUE'), valueType: 'BOOLEAN' };
            case 'cmd_var_ref':
                return { type: 'var-ref', name: block.getFieldValue('NAME') };
            case 'cmd_context_get':
                return { type: 'context-get', path: block.getFieldValue('PATH') };
            case 'cmd_call_igdb_get_game':
                return {
                    type: 'service-call', namespace: 'igdb', function: 'getGame',
                    args: [exprBlockToNode(block.getInputTargetBlock('QUERY'))]
                };
            case 'cmd_call_twitch_get_user':
                return { type: 'service-call', namespace: 'twitch', function: 'getUser', args: [] };
            case 'cmd_call_steam_get_price':
                return {
                    type: 'service-call', namespace: 'steam', function: 'getPrice',
                    args: [exprBlockToNode(block.getInputTargetBlock('APP_ID'))]
                };
            default:
                throw new Error('Unknown expression block type: ' + block.type);
        }
    }

    function statementBlockToNode(block) {
        switch (block.type) {
            case 'cmd_var_decl': {
                var init = exprBlockToNode(block.getInputTargetBlock('INIT'));
                return { type: 'var-decl', name: block.getFieldValue('NAME'), valueType: init.valueType || 'STRING', init: init };
            }
            case 'cmd_assign':
                return { type: 'assign', name: block.getFieldValue('NAME'), expr: exprBlockToNode(block.getInputTargetBlock('EXPR')) };
            case 'cmd_concat':
                return { type: 'concat', name: block.getFieldValue('NAME'), expr: exprBlockToNode(block.getInputTargetBlock('EXPR')) };
            case 'cmd_print':
                return { type: 'print', expr: exprBlockToNode(block.getInputTargetBlock('EXPR')) };
            case 'cmd_if':
                return {
                    type: 'if',
                    condition: {
                        type: 'binary',
                        left: exprBlockToNode(block.getInputTargetBlock('LEFT')),
                        operator: block.getFieldValue('OPERATOR'),
                        right: exprBlockToNode(block.getInputTargetBlock('RIGHT'))
                    },
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
                throw new Error('Unknown statement block type: ' + block.type);
        }
    }

    function statementsToNodes(firstBlock) {
        const nodes = [];
        let block = firstBlock;
        while (block) {
            nodes.push(statementBlockToNode(block));
            block = block.getNextBlock();
        }
        return nodes;
    }

    function blocksToAst() {
        // getTopBlocks returns only the HEAD of each connected stack — walk each chain
        // with statementsToNodes (same helper used for nested if/for-each bodies) instead
        // of converting just the first block, or every statement after the first one in
        // a stack silently disappears on save.
        const top = ensureWorkspace().getTopBlocks(true);
        const statements = [];
        for (const block of top) {
            statements.push(...statementsToNodes(block));
        }
        return { statements: statements };
    }

    // ---- AST -> Blocks ----

    function exprNodeToBlock(ws, node) {
        let block;
        switch (node.type) {
            case 'literal':
                if (node.valueType === 'NUMBER') { block = ws.newBlock('cmd_literal_number'); block.setFieldValue(Number(node.value), 'VALUE'); }
                else if (node.valueType === 'BOOLEAN') { block = ws.newBlock('cmd_literal_boolean'); block.setFieldValue(node.value, 'VALUE'); }
                else { block = ws.newBlock('cmd_literal_string'); block.setFieldValue(node.value, 'VALUE'); }
                break;
            case 'var-ref':
                block = ws.newBlock('cmd_var_ref');
                block.setFieldValue(node.name, 'NAME');
                break;
            case 'context-get':
                block = ws.newBlock('cmd_context_get');
                block.setFieldValue(node.path, 'PATH');
                break;
            case 'service-call':
                if (node.namespace === 'igdb' && node.function === 'getGame') {
                    block = ws.newBlock('cmd_call_igdb_get_game');
                    connectValue(ws, block, 'QUERY', node.args[0]);
                } else if (node.namespace === 'twitch' && node.function === 'getUser') {
                    block = ws.newBlock('cmd_call_twitch_get_user');
                } else if (node.namespace === 'steam' && node.function === 'getPrice') {
                    block = ws.newBlock('cmd_call_steam_get_price');
                    connectValue(ws, block, 'APP_ID', node.args[0]);
                } else {
                    throw new Error('Unsupported service call in Blocks view: ' + node.namespace + '#' + node.function);
                }
                break;
            default:
                throw new Error('Unknown expression node type for blocks view: ' + node.type);
        }
        block.initSvg();
        block.render();
        return block;
    }

    function connectValue(ws, parentBlock, inputName, node) {
        if (!node) return;
        const child = exprNodeToBlock(ws, node);
        parentBlock.getInput(inputName).connection.connect(child.outputConnection);
    }

    function statementNodeToBlock(ws, node) {
        let block;
        switch (node.type) {
            case 'var-decl':
                block = ws.newBlock('cmd_var_decl');
                block.setFieldValue(node.name, 'NAME');
                connectValue(ws, block, 'INIT', node.init);
                break;
            case 'assign':
                block = ws.newBlock('cmd_assign');
                block.setFieldValue(node.name, 'NAME');
                connectValue(ws, block, 'EXPR', node.expr);
                break;
            case 'concat':
                block = ws.newBlock('cmd_concat');
                block.setFieldValue(node.name, 'NAME');
                connectValue(ws, block, 'EXPR', node.expr);
                break;
            case 'print':
                block = ws.newBlock('cmd_print');
                connectValue(ws, block, 'EXPR', node.expr);
                break;
            case 'if':
                block = ws.newBlock('cmd_if');
                connectValue(ws, block, 'LEFT', node.condition.left);
                block.setFieldValue(node.condition.operator, 'OPERATOR');
                connectValue(ws, block, 'RIGHT', node.condition.right);
                connectStatements(ws, block, 'THEN', node.then);
                connectStatements(ws, block, 'ELSE', node.else);
                break;
            case 'for-each':
                block = ws.newBlock('cmd_for_each');
                block.setFieldValue(node.bindingName, 'BINDING');
                block.setFieldValue(node.listSource, 'LIST_SOURCE');
                connectStatements(ws, block, 'BODY', node.body);
                break;
            default:
                throw new Error('Unknown statement node type for blocks view: ' + node.type);
        }
        block.initSvg();
        block.render();
        return block;
    }

    function connectStatements(ws, parentBlock, inputName, nodes) {
        let previous = null;
        for (const node of (nodes || [])) {
            const block = statementNodeToBlock(ws, node);
            if (previous) {
                previous.nextConnection.connect(block.previousConnection);
            } else {
                parentBlock.getInput(inputName).connection.connect(block.previousConnection);
            }
            previous = block;
        }
    }

    function astToBlocks(ast) {
        const ws = ensureWorkspace();
        ws.clear();
        let previous = null;
        for (const node of (ast.statements || [])) {
            let block;
            try {
                block = statementNodeToBlock(ws, node);
            } catch (err) {
                console.warn('chat-command-editor: skipping statement in Blocks view', node, err);
                continue;
            }
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
        console.error('chat-command-editor:', err);
        const text = err && err.message ? err.message : String(err);
        if (window.catapultOverlay && window.catapultOverlay.showToast) {
            window.catapultOverlay.showToast(text, { kind: 'error', duration: 6000 });
        } else {
            alert(text);
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
            // Refuse to persist a conversion that silently dropped everything (e.g. an
            // AST/Blocks node type the current view can't represent, or a broken
            // Blockly workspace) — better to block the save with a clear error than
            // to overwrite a working command with an empty one.
            if ((!ast.statements || ast.statements.length === 0) &&
                document.getElementById('ceTextArea').value.trim() !== '') {
                reportEditorError(new Error(
                    'La conversion a produit une commande vide alors que du texte existe — ' +
                    'sauvegarde annulée pour éviter d\'écraser la commande. Vérifie l\'onglet Texte/Blocs.'));
                return;
            }
            // This modal has no fallback-editing UI of its own — currentCmd.fallbacks is the
            // list of {placeholder, fallbackText} the command already had (see CommandDto).
            // Re-send it as-is; sending {} here would silently wipe every configured fallback
            // on every Blocks/Text save.
            const fallbacks = {};
            (currentCmd.fallbacks || []).forEach(fb => { fallbacks[fb.placeholder] = fb.fallbackText; });
            await window.catapultWs.request('chat-commands.update', {
                id: currentCmd.id,
                name: document.getElementById('ceName').value,
                template: document.getElementById('ceTextArea').value,
                permission: currentCmd.permission,
                enabled: currentCmd.enabled,
                fallbacks: fallbacks,
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
    });
})();

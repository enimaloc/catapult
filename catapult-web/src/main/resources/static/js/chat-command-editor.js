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

    // ---- Server-driven catalog (known context paths + registered service functions) ----
    //
    // The server is authoritative for what a streamer can build: known context paths
    // (PlaceholderResolver#KNOWN_PATHS) and registered service functions
    // (ServiceFunctionRegistry) both live in catapult-api. This client never hardcodes
    // its own copy — it fetches the catalog once and renders whatever it reports, so
    // registering a new ServiceFunction bean picks up a Blockly block automatically.

    let catalog = null;

    async function ensureCatalog() {
        if (catalog) return catalog;
        const resp = await window.catapultWs.request('chat-commands.dsl.catalog', {});
        catalog = resp.result;
        return catalog;
    }

    // ---- Blockly custom blocks (mirror the Statement/Expression node types) ----
    //
    // "Interface" (JS has no language-level interface — this is the documented contract
    // every block class/instance below satisfies, dispatched on duck-typed shape):
    //   .type                    — the Blockly block type identifier
    //   .category()              — which toolbox category this block belongs to
    //   .definition(catalog)      — the Blockly JSON block definition (message0/args0/colour/...)
    //   .toNode(block)            — reads a Blockly Block instance into its AST node shape
    //   .fromNode(ws, node)       — builds (but doesn't initSvg/render) a Block from an AST node
    // Static block classes implement it with static members. ServiceCallBlock (built per
    // registered function from the server catalog, unknown at author time) implements the
    // same names as instance members on a per-function instance — `registry[type].toNode(...)`
    // calls identically either way, so the two "shapes" are interchangeable at every call site.

    class CmdLiteralStringBlock {
        static type = 'cmd_literal_string';
        static nodeType = null; // handled specially in nodeToBlock() — 'literal' needs valueType branching
        static category() { return 'Texte'; }
        static definition() {
            return { type: this.type, message0: '" %1 "',
                args0: [{ type: 'field_input', name: 'VALUE', text: '' }], output: null, colour: 60 };
        }
        static toNode(block) {
            return { type: 'literal', value: block.getFieldValue('VALUE'), valueType: 'STRING' };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.value, 'VALUE');
            return block;
        }
    }

    class CmdLiteralNumberBlock {
        static type = 'cmd_literal_number';
        static category() { return 'Texte'; }
        static definition() {
            return { type: this.type, message0: '# %1',
                args0: [{ type: 'field_number', name: 'VALUE', value: 0 }], output: null, colour: 65 };
        }
        static toNode(block) {
            return { type: 'literal', value: String(block.getFieldValue('VALUE')), valueType: 'NUMBER' };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(Number(node.value), 'VALUE');
            return block;
        }
    }

    class CmdLiteralBooleanBlock {
        static type = 'cmd_literal_boolean';
        static category() { return 'Texte'; }
        static definition() {
            return { type: this.type, message0: '%1',
                args0: [{ type: 'field_dropdown', name: 'VALUE', options: [['true', 'true'], ['false', 'false']] }],
                output: null, colour: 70 };
        }
        static toNode(block) {
            return { type: 'literal', value: block.getFieldValue('VALUE'), valueType: 'BOOLEAN' };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.value, 'VALUE');
            return block;
        }
    }

    class CmdVarRefBlock {
        static type = 'cmd_var_ref';
        static nodeType = 'var-ref';
        static category() { return 'Variables'; }
        static definition() {
            return { type: this.type, message0: 'var %1',
                args0: [{ type: 'field_input', name: 'NAME', text: 'msg' }], output: null, colour: 150 };
        }
        static toNode(block) {
            return { type: 'var-ref', name: block.getFieldValue('NAME') };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.name, 'NAME');
            return block;
        }
    }

    class CmdContextGetBlock {
        static type = 'cmd_context_get';
        static nodeType = 'context-get';
        static category() { return 'Contexte'; }
        static definition(catalog) {
            return { type: this.type, message0: 'get %1',
                args0: [{ type: 'field_dropdown', name: 'PATH', options: catalog.contextPaths.map(p => [p, p]) }],
                output: null, colour: 200 };
        }
        static toNode(block) {
            return { type: 'context-get', path: block.getFieldValue('PATH') };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.path, 'PATH');
            return block;
        }
    }

    class CmdVarDeclBlock {
        static type = 'cmd_var_decl';
        static nodeType = 'var-decl';
        static category() { return 'Variables'; }
        static definition() {
            return { type: this.type, message0: 'var %1 = %2',
                args0: [{ type: 'field_input', name: 'NAME', text: 'msg' }, { type: 'input_value', name: 'INIT' }],
                previousStatement: null, nextStatement: null, colour: 20 };
        }
        static toNode(block) {
            const init = blockToNode(block.getInputTargetBlock('INIT'));
            return { type: 'var-decl', name: block.getFieldValue('NAME'), valueType: init.valueType || 'STRING', init: init };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.name, 'NAME');
            connectValue(ws, block, 'INIT', node.init);
            return block;
        }
    }

    class CmdAssignBlock {
        static type = 'cmd_assign';
        static nodeType = 'assign';
        static category() { return 'Variables'; }
        static definition() {
            return { type: this.type, message0: '%1 = %2',
                args0: [{ type: 'field_input', name: 'NAME', text: 'msg' }, { type: 'input_value', name: 'EXPR' }],
                previousStatement: null, nextStatement: null, colour: 20 };
        }
        static toNode(block) {
            return { type: 'assign', name: block.getFieldValue('NAME'), expr: blockToNode(block.getInputTargetBlock('EXPR')) };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.name, 'NAME');
            connectValue(ws, block, 'EXPR', node.expr);
            return block;
        }
    }

    class CmdConcatBlock {
        static type = 'cmd_concat';
        static nodeType = 'concat';
        static category() { return 'Variables'; }
        static definition() {
            return { type: this.type, message0: '%1 += %2',
                args0: [{ type: 'field_input', name: 'NAME', text: 'msg' }, { type: 'input_value', name: 'EXPR' }],
                previousStatement: null, nextStatement: null, colour: 20 };
        }
        static toNode(block) {
            return { type: 'concat', name: block.getFieldValue('NAME'), expr: blockToNode(block.getInputTargetBlock('EXPR')) };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.name, 'NAME');
            connectValue(ws, block, 'EXPR', node.expr);
            return block;
        }
    }

    class CmdPrintBlock {
        static type = 'cmd_print';
        static nodeType = 'print';
        static category() { return 'Texte'; }
        static definition() {
            return { type: this.type, message0: 'print %1',
                args0: [{ type: 'input_value', name: 'EXPR' }],
                previousStatement: null, nextStatement: null, colour: 40 };
        }
        static toNode(block) {
            return { type: 'print', expr: blockToNode(block.getInputTargetBlock('EXPR')) };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            connectValue(ws, block, 'EXPR', node.expr);
            return block;
        }
    }

    class CmdIfBlock {
        static type = 'cmd_if';
        static nodeType = 'if';
        static category() { return 'Contrôle'; }
        static definition() {
            return { type: this.type, message0: 'if %1 %2 %3 then %4 else %5',
                args0: [
                    { type: 'input_value', name: 'LEFT' },
                    { type: 'field_dropdown', name: 'OPERATOR',
                        options: [['==', '=='], ['!=', '!='], ['<', '<'], ['>', '>'], ['<=', '<='], ['>=', '>=']] },
                    { type: 'input_value', name: 'RIGHT' },
                    { type: 'input_statement', name: 'THEN' },
                    { type: 'input_statement', name: 'ELSE' }
                ],
                previousStatement: null, nextStatement: null, colour: 210 };
        }
        static toNode(block) {
            return {
                type: 'if',
                condition: {
                    type: 'binary',
                    left: blockToNode(block.getInputTargetBlock('LEFT')),
                    operator: block.getFieldValue('OPERATOR'),
                    right: blockToNode(block.getInputTargetBlock('RIGHT'))
                },
                then: statementsToNodes(block.getInputTargetBlock('THEN')),
                else: statementsToNodes(block.getInputTargetBlock('ELSE'))
            };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            connectValue(ws, block, 'LEFT', node.condition.left);
            block.setFieldValue(node.condition.operator, 'OPERATOR');
            connectValue(ws, block, 'RIGHT', node.condition.right);
            connectStatements(ws, block, 'THEN', node.then);
            connectStatements(ws, block, 'ELSE', node.else);
            return block;
        }
    }

    class CmdForEachBlock {
        static type = 'cmd_for_each';
        static nodeType = 'for-each';
        static category() { return 'Contrôle'; }
        static definition() {
            return { type: this.type, message0: 'for each %1 in %2 %3',
                args0: [
                    { type: 'field_input', name: 'BINDING', text: 'f' },
                    { type: 'field_input', name: 'LIST_SOURCE', text: 'fallbacks' },
                    { type: 'input_statement', name: 'BODY' }
                ],
                previousStatement: null, nextStatement: null, colour: 120 };
        }
        static toNode(block) {
            return {
                type: 'for-each',
                bindingName: block.getFieldValue('BINDING'),
                listSource: block.getFieldValue('LIST_SOURCE'),
                body: statementsToNodes(block.getInputTargetBlock('BODY'))
            };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.bindingName, 'BINDING');
            block.setFieldValue(node.listSource, 'LIST_SOURCE');
            connectStatements(ws, block, 'BODY', node.body);
            return block;
        }
    }

    // ServiceCallExpr: one instance per registered function, built from whatever the server
    // currently reports — satisfies the same {type, category, definition, toNode, fromNode}
    // contract as the static classes above, but as instance members parametrized by `fn`,
    // since the function catalog isn't known until the catalog loads.
    class ServiceCallBlock {
        constructor(fn) {
            this.fn = fn;
            this.type = 'cmd_call_' + fn.namespace + '_' + fn.name;
        }
        category() { return 'Fonctions'; }
        definition() {
            const argRefs = this.fn.parameterNames.map((name, i) => name + ': %' + (i + 1)).join(', ');
            return {
                type: this.type,
                message0: this.fn.namespace + '.' + this.fn.name + '(' + argRefs + ')',
                args0: this.fn.parameterNames.map((name, i) => ({ type: 'input_value', name: 'ARG' + i })),
                output: null,
                colour: 290
            };
        }
        toNode(block) {
            const args = this.fn.parameterNames.map((name, i) => blockToNode(block.getInputTargetBlock('ARG' + i)));
            return { type: 'service-call', namespace: this.fn.namespace, function: this.fn.name, args: args };
        }
        fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            this.fn.parameterNames.forEach((name, i) => connectValue(ws, block, 'ARG' + i, node.args[i]));
            return block;
        }
    }

    const STATIC_BLOCK_CLASSES = [
        CmdLiteralStringBlock, CmdLiteralNumberBlock, CmdLiteralBooleanBlock,
        CmdVarRefBlock, CmdContextGetBlock,
        CmdVarDeclBlock, CmdAssignBlock, CmdConcatBlock, CmdPrintBlock, CmdIfBlock, CmdForEachBlock
    ];
    const TOOLBOX_CATEGORY_ORDER = ['Variables', 'Contrôle', 'Texte', 'Contexte', 'Fonctions'];
    const TOOLBOX_CATEGORY_COLOURS = { Variables: '20', 'Contrôle': '210', Texte: '60', Contexte: '200', Fonctions: '290' };

    // blockly type -> class/instance implementing the contract above — the single dispatch
    // table for blockToNode(). AST-node-type -> class/instance for the reverse direction is
    // built inline in nodeToBlock() below (literal/service-call need special-casing there;
    // everything else is a 1:1 lookup via each class's static `nodeType`).
    let blockRegistry = {};

    function ensureBlocksDefined() {
        if (Object.keys(blockRegistry).length) return;
        const serviceCallBlocks = catalog.serviceFunctions.map(fn => new ServiceCallBlock(fn));
        const all = [...STATIC_BLOCK_CLASSES, ...serviceCallBlocks];
        all.forEach(b => { blockRegistry[b.type] = b; });
        Blockly.defineBlocksWithJsonArray(all.map(b => b.definition(catalog)));
    }

    // ensureWorkspace() is only ever reached after ensureCatalog() has resolved
    // (openEditor awaits it first), so `catalog` is guaranteed populated here.
    function ensureWorkspace() {
        if (workspace) return workspace;
        ensureBlocksDefined();
        const byCategory = {};
        Object.values(blockRegistry).forEach(b => {
            (byCategory[b.category()] = byCategory[b.category()] || []).push(b);
        });
        workspace = Blockly.inject('ceBlocksPane', {
            toolbox: {
                kind: "categoryToolbox",
                contents: TOOLBOX_CATEGORY_ORDER.filter(name => byCategory[name]).map(name => ({
                    kind: "category", name: name, colour: TOOLBOX_CATEGORY_COLOURS[name],
                    contents: byCategory[name].map(b => ({ kind: "block", type: b.type }))
                }))
            },
            theme: buildBlocklyTheme()
        });
        workspace.resize();
        return workspace;
    }

    // ---- Blocks -> AST ----

    function blockToNode(block) {
        if (!block) return { type: 'literal', value: '', valueType: 'STRING' };
        const descriptor = blockRegistry[block.type];
        if (!descriptor) throw new Error('Unknown block type: ' + block.type);
        return descriptor.toNode(block);
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

    function nodeToBlock(ws, node) {
        let block;
        if (node.type === 'literal') {
            const cls = node.valueType === 'NUMBER' ? CmdLiteralNumberBlock
                : node.valueType === 'BOOLEAN' ? CmdLiteralBooleanBlock
                : CmdLiteralStringBlock;
            block = cls.fromNode(ws, node);
        } else if (node.type === 'service-call') {
            const fn = catalog.serviceFunctions.find(f => f.namespace === node.namespace && f.name === node.function);
            if (!fn) {
                throw new Error('Unsupported service call in Blocks view: ' + node.namespace + '#' + node.function);
            }
            block = new ServiceCallBlock(fn).fromNode(ws, node);
        } else {
            const descriptor = Object.values(blockRegistry).find(b => b.nodeType === node.type);
            if (!descriptor) throw new Error('Unknown node type for blocks view: ' + node.type);
            block = descriptor.fromNode(ws, node);
        }
        block.initSvg();
        block.render();
        return block;
    }

    function connectValue(ws, parentBlock, inputName, node) {
        if (!node) return;
        const child = nodeToBlock(ws, node);
        parentBlock.getInput(inputName).connection.connect(child.outputConnection);
    }

    function connectStatements(ws, parentBlock, inputName, nodes) {
        let previous = null;
        for (const node of (nodes || [])) {
            const block = nodeToBlock(ws, node);
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
                block = nodeToBlock(ws, node);
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

    // Read-only (Phase 1 scope — see design spec's Block Editor section: editing the
    // generated JS is Phase 2, behind the disabled "Éjecter" button).
    async function astToJs(ast) {
        const resp = await window.catapultWs.request('chat-commands.dsl.ast-to-js', { ast: JSON.stringify(ast) });
        return resp.result.js;
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
            await ensureCatalog();
            renderTestOverrideFields();
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
            } else if (tab === 'js') {
                // Read-only projection (Phase 1 scope) — computed from whichever of
                // Blocks/Text is currently authoritative, via currentAst() below.
                document.getElementById('ceJsOutput').textContent = await astToJs(await currentAst());
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

    // One labelled input per known context path (catalog.contextPaths), so the streamer
    // can test branches/text that depend on a placeholder without actually being live
    // with that value (e.g. testing the "no game" wording while off-stream).
    function renderTestOverrideFields() {
        const container = document.getElementById('ceTestOverrides');
        container.replaceChildren();
        for (const path of catalog.contextPaths) {
            const row = document.createElement('div');
            row.style.display = 'flex';
            row.style.gap = '.5rem';
            row.style.alignItems = 'center';
            const label = document.createElement('label');
            label.textContent = path;
            label.style.cssText = 'font-family:monospace; font-size:.85em; min-width:160px;';
            const input = document.createElement('input');
            input.type = 'text';
            input.className = 'form-input';
            input.dataset.overridePath = path;
            input.style.flex = '1';
            row.appendChild(label);
            row.appendChild(input);
            container.appendChild(row);
        }
    }

    function collectTestOverrides() {
        const overrides = {};
        document.querySelectorAll('#ceTestOverrides [data-override-path]').forEach(input => {
            const value = input.value.trim();
            if (value !== '') overrides[input.dataset.overridePath] = value;
        });
        return overrides;
    }

    document.getElementById('ceTestBtn').onclick = async () => {
        if (!currentCmd) return;
        try {
            const resp = await window.catapultWs.request('chat-commands.test', {
                id: currentCmd.id,
                overrides: collectTestOverrides()
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

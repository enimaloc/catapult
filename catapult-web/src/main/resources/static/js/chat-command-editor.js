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

    // Reads one of the streamer's own free-form key=value settings (ChatCommandSetting,
    // ctx.settings.<key> — distinct from ContextGetExpr/ctx.a.b, which is a fixed known-path
    // catalog). Settings are per-streamer and open-ended, so KEY is a free-text field rather
    // than a dropdown (which would break with zero saved settings) — pre-filled from the
    // streamer's first saved key as a hint when one exists.
    class CmdSettingGetBlock {
        static type = 'cmd_setting_get';
        static nodeType = 'setting-get';
        static category() { return 'Contexte'; }
        static definition(catalog) {
            return { type: this.type, message0: 'setting %1',
                args0: [{ type: 'field_input', name: 'KEY', text: (catalog.settingKeys[0] || 'key') }],
                output: null, colour: 200 };
        }
        static toNode(block) {
            return { type: 'setting-get', key: block.getFieldValue('KEY') };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.key, 'KEY');
            return block;
        }
    }

    // Reads one of the whitespace-split words typed after the command name (e.g. "!shoutout
    // myfriend" -> arg(0) === "myfriend"). Index is a fixed, non-negative literal (ArgGetExpr
    // only supports a literal int today, see docs/specs/2026-07-24-chat-command-args-design.md),
    // so a number field is enough here — no dropdown, no expression slot for the index.
    class CmdArgGetBlock {
        static type = 'cmd_arg_get';
        static nodeType = 'arg-get';
        static category() { return 'Contexte'; }
        static definition() {
            return { type: this.type, message0: 'arg %1',
                args0: [{ type: 'field_number', name: 'INDEX', value: 0, min: 0, precision: 1 }],
                output: null, colour: 200 };
        }
        static toNode(block) {
            return { type: 'arg-get', index: block.getFieldValue('INDEX') };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.index, 'INDEX');
            return block;
        }
    }

    // Groups several related values into one variable, e.g. {name: "Valorant", price: 29.99}.
    // The PROPERTIES slot holds a chain of CmdObjectPropertyBlock, reusing the exact same
    // statementsToNodes/connectStatements chaining already used for if/for-each bodies —
    // 'object-property' isn't a real AST Statement, it's a synthetic shape that only exists
    // during this Blocks<->AST bridging, converted into the real {properties: {...}} map by
    // CmdObjectLiteralBlock itself.
    class CmdObjectPropertyBlock {
        static type = 'cmd_object_property';
        static nodeType = 'object-property';
        static category() { return 'Variables'; }
        static definition() {
            return { type: this.type, message0: '%1 : %2',
                args0: [{ type: 'field_input', name: 'KEY', text: 'key' }, { type: 'input_value', name: 'VALUE' }],
                previousStatement: null, nextStatement: null, colour: 25 };
        }
        static toNode(block) {
            return { type: 'object-property', key: block.getFieldValue('KEY'), value: blockToNode(block.getInputTargetBlock('VALUE')) };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.key, 'KEY');
            connectValue(ws, block, 'VALUE', node.value);
            return block;
        }
    }

    class CmdObjectLiteralBlock {
        static type = 'cmd_object_literal';
        static nodeType = 'object-literal';
        static category() { return 'Variables'; }
        static definition() {
            return { type: this.type, message0: '{ %1 }',
                args0: [{ type: 'input_statement', name: 'PROPERTIES' }],
                output: null, colour: 25 };
        }
        static toNode(block) {
            const propNodes = statementsToNodes(block.getInputTargetBlock('PROPERTIES'));
            const properties = {};
            propNodes.forEach(p => { properties[p.key] = p.value; });
            return { type: 'object-literal', properties: properties };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            const propNodes = Object.entries(node.properties).map(([key, value]) => ({ type: 'object-property', key, value }));
            connectStatements(ws, block, 'PROPERTIES', propNodes);
            return block;
        }
    }

    // Field names available on whatever is connected to a cmd_property_get block's TARGET —
    // either an object-literal's own (user-typed) keys, or a service function's declared
    // returnKeys() (catalog-driven, e.g. twitch#getStream() -> title/category/viewers/uptime).
    // Backs the PROPERTY dropdown below so streamers pick a real field instead of typing one
    // from memory and silently getting "" back on a typo.
    function computeTargetKeys(targetBlock) {
        if (!targetBlock) return [];
        if (targetBlock.type === 'cmd_object_literal') {
            const keys = [];
            let propBlock = targetBlock.getInputTargetBlock('PROPERTIES');
            while (propBlock) {
                const key = propBlock.getFieldValue('KEY');
                if (key) keys.push(key);
                propBlock = propBlock.getNextBlock();
            }
            return keys;
        }
        const descriptor = blockRegistry[targetBlock.type];
        if (descriptor && descriptor.fn && Array.isArray(descriptor.fn.returnKeys)) {
            return descriptor.fn.returnKeys;
        }
        return [];
    }

    // Blockly calls a FieldDropdown's function-form menuGenerator with `this` bound to the
    // field itself, re-invoking it fresh every time the dropdown is opened — so this always
    // reflects whatever is currently wired into TARGET, no manual refresh/onchange wiring needed.
    function propertyDropdownGenerator() {
        const block = this.getSourceBlock();
        const keys = block ? computeTargetKeys(block.getInputTargetBlock('TARGET')) : [];
        const current = this.getValue();
        const options = keys.slice();
        if (current && !options.includes(current)) options.push(current);
        return options.length ? options.map(k => [k, k]) : [['?', '']];
    }

    // JSON block definitions can't reference a JS function for a dropdown's options (JSON is
    // data-only), so cmd_property_get is defined with a static placeholder PROPERTY field and
    // this extension swaps it for a real function-generated Blockly.FieldDropdown right after
    // construction — the officially supported way to build a dynamic dropdown on a JSON block.
    Blockly.Extensions.register('cmd_property_get_dynamic_field', function () {
        const block = this;
        const input = block.inputList.find(i => i.fieldRow.some(f => f.name === 'PROPERTY'));
        const index = input.fieldRow.findIndex(f => f.name === 'PROPERTY');
        const savedValue = input.fieldRow[index].getValue();
        input.removeField('PROPERTY');
        const field = new Blockly.FieldDropdown(propertyDropdownGenerator);
        input.insertFieldAt(index, field, 'PROPERTY');
        if (savedValue) field.setValue(savedValue);
    });

    // get(obj, "property") — deliberately bracket-style in the label ("[ ]"), not "obj.name":
    // this app avoids '.' in anything that could reach chat text (Twitch/mod bots flag
    // dot-paths as links, see V59__placeholder_hash_separator.sql), so the DSL text this
    // block round-trips through never introduces new '.' syntax either.
    class CmdPropertyGetBlock {
        static type = 'cmd_property_get';
        static nodeType = 'property-get';
        static category() { return 'Variables'; }
        static definition() {
            return { type: this.type, message0: 'get %1 [ %2 ]',
                args0: [
                    { type: 'input_value', name: 'TARGET' },
                    { type: 'field_dropdown', name: 'PROPERTY', options: [['?', '']] }
                ],
                output: null, colour: 25,
                extensions: ['cmd_property_get_dynamic_field']
            };
        }
        static toNode(block) {
            return { type: 'property-get', target: blockToNode(block.getInputTargetBlock('TARGET')), property: block.getFieldValue('PROPERTY') };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            connectValue(ws, block, 'TARGET', node.target);
            block.setFieldValue(node.property, 'PROPERTY');
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
            // Mirrors CommandDslParser#parseVarDecl's inference: a literal's own type, OBJECT
            // for an object literal (which carries no valueType of its own), else STRING.
            const valueType = init.type === 'literal' ? init.valueType
                : init.type === 'object-literal' ? 'OBJECT' : 'STRING';
            return { type: 'var-decl', name: block.getFieldValue('NAME'), valueType: valueType, init: init };
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
        category() { return namespaceCategory(this.fn.namespace); }
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
        CmdVarRefBlock, CmdContextGetBlock, CmdSettingGetBlock, CmdArgGetBlock,
        CmdObjectLiteralBlock, CmdObjectPropertyBlock, CmdPropertyGetBlock,
        CmdVarDeclBlock, CmdAssignBlock, CmdConcatBlock, CmdPrintBlock, CmdIfBlock, CmdForEachBlock
    ];
    const TOOLBOX_CATEGORY_ORDER = ['Variables', 'Contrôle', 'Texte', 'Contexte'];
    const TOOLBOX_CATEGORY_COLOURS = { Variables: '20', 'Contrôle': '210', Texte: '60', Contexte: '200' };
    // Registered functions get their own toolbox category per namespace instead of one shared
    // "Fonctions" bucket, so a growing function catalog stays browsable (Twitch/Steam/Catapult/
    // IGDB, one tab each) instead of piling every service into a single flat list.
    const SERVICE_CATEGORY_DISPLAY_NAMES = { igdb: 'IGDB' };
    function namespaceCategory(namespace) {
        return SERVICE_CATEGORY_DISPLAY_NAMES[namespace]
            || (namespace.charAt(0).toUpperCase() + namespace.slice(1));
    }

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
        // Static categories first in their fixed order, then one category per service
        // namespace (sorted alphabetically) for whatever the catalog currently reports.
        const serviceCategoryNames = Object.keys(byCategory)
            .filter(name => !TOOLBOX_CATEGORY_ORDER.includes(name))
            .sort();
        const categoryOrder = [...TOOLBOX_CATEGORY_ORDER, ...serviceCategoryNames];
        workspace = Blockly.inject('ceBlocksPane', {
            toolbox: {
                kind: "categoryToolbox",
                contents: categoryOrder.filter(name => byCategory[name]).map(name => ({
                    kind: "category", name: name, colour: TOOLBOX_CATEGORY_COLOURS[name] || '290',
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

    // "Eject to JS" (Phase 2): non-null means the command runs this JS directly at dispatch,
    // bypassing Blocks/Text/AST entirely. Reversible — ast/template are never derived from
    // this, "Revenir" just clears it. Kept live in sync with the textarea while editing.
    let ejectedJs = null;

    function updateEjectUI() {
        const isEjected = ejectedJs !== null;
        document.getElementById('ceEjectedBanner').hidden = !isEjected;
        document.getElementById('ceEjectBtn').hidden = isEjected;
        document.getElementById('ceRevertBtn').hidden = !isEjected;
        document.getElementById('ceJsOutput').readOnly = !isEjected;
        // Editing Blocks/Text while ejected wouldn't affect what actually runs — block it
        // rather than let the streamer edit a view that's silently ignored.
        document.querySelectorAll('.ce-tab-btn').forEach(btn => {
            if (btn.dataset.tab === 'blocks' || btn.dataset.tab === 'text') {
                btn.disabled = isEjected;
                btn.title = isEjected ? 'Reviens aux Blocs/Texte pour éditer' : '';
            }
        });
    }

    async function openEditor(cmd) {
        currentCmd = cmd;
        const isBuiltin = !!(cmd.presetKey && cmd.presetKey.indexOf('builtin:') === 0);
        document.getElementById('ceName').value = cmd.name;
        document.getElementById('ceName').disabled = isBuiltin;
        document.getElementById('ceTextArea').value = cmd.template || '';
        document.getElementById('ceTraceOutput').hidden = true;
        document.getElementById('ceScopeWarning').hidden = true;
        document.getElementById('chatCommandEditorModal').hidden = false;
        document.getElementById('chatCommandEditorModal').style.display = 'flex';
        ejectedJs = cmd.ejectedJs || null;
        if (ejectedJs !== null) document.getElementById('ceJsOutput').value = ejectedJs;
        updateEjectUI();
        currentTab = ejectedJs !== null ? 'js' : 'blocks';
        selectTabUI(currentTab);
        try {
            await ensureCatalog();
            renderTestOverrideFields();
            if (ejectedJs === null) astToBlocks(await textToAst(cmd.template || ''));
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
            } else if (tab === 'js' && ejectedJs === null) {
                // Projection of whichever of Blocks/Text is currently authoritative, via
                // currentAst() below — read-only until "Éjecter" is clicked.
                document.getElementById('ceJsOutput').value = await astToJs(await currentAst());
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
            // This modal has no fallback-editing UI of its own — currentCmd.fallbacks is the
            // list of {placeholder, fallbackText} the command already had (see CommandDto).
            // Re-send it as-is; sending {} here would silently wipe every configured fallback
            // on every save.
            const fallbacks = {};
            (currentCmd.fallbacks || []).forEach(fb => { fallbacks[fb.placeholder] = fb.fallbackText; });

            const payload = {
                id: currentCmd.id,
                name: document.getElementById('ceName').value,
                permission: currentCmd.permission,
                enabled: currentCmd.enabled,
                fallbacks: fallbacks
            };

            if (ejectedJs !== null) {
                // Reversibility: ast/template stay exactly as they already were — only the
                // hand-written JS changes. "Revenir" later restores editing from that
                // untouched AST, none of which was ever derived from this JS.
                payload.template = currentCmd.template;
                payload.ejectedJs = ejectedJs;
            } else {
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
                payload.template = document.getElementById('ceTextArea').value;
                payload.ast = JSON.stringify(ast);
                // Explicit clear: covers reverting a previously-ejected command.
                payload.ejectedJs = '';
            }

            const result = await window.catapultWs.request('chat-commands.update', payload);
            const missingScopes = (result && result.missingTwitchScopes) || [];
            document.getElementById('ceScopeWarning').hidden = missingScopes.length === 0;
            if (window.chatCommandEditor.onSaved) window.chatCommandEditor.onSaved();
            // Keep the editor open when a scope gap was just detected, so the streamer sees
            // the warning and the "Reconnecter Twitch" link — otherwise close as before.
            if (missingScopes.length === 0) closeEditor();
        } catch (err) {
            reportEditorError(err);
        }
    };

    document.getElementById('ceEjectBtn').onclick = () => {
        ejectedJs = document.getElementById('ceJsOutput').value;
        updateEjectUI();
    };

    document.getElementById('ceRevertBtn').onclick = async () => {
        ejectedJs = null;
        updateEjectUI();
        try {
            document.getElementById('ceJsOutput').value = await astToJs(await currentAst());
        } catch (err) {
            reportEditorError(err);
        }
    };

    document.getElementById('ceJsOutput').addEventListener('input', e => {
        if (ejectedJs !== null) ejectedJs = e.target.value;
    });

    // Kept at module scope (not reset per command) so test values a streamer typed while
    // testing one command are still there when they open a different one — the same
    // {game#name: "Valorant", ...} test rig is usually reused across several commands in
    // a row, not re-typed for each.
    let persistedTestOverrides = {};

    // One labelled input per known context path (catalog.contextPaths), so the streamer
    // can test branches/text that depend on a placeholder without actually being live
    // with that value (e.g. testing the "no game" wording while off-stream). Stacked
    // label-above-input per cell of the grid — a side-by-side layout doesn't leave enough
    // room for paths like "game#store#battlenet" at the grid's ~220px column width.
    function renderTestOverrideFields() {
        // Snapshot whatever's currently filled in (from editing the previous command, if
        // any) into the persisted store before the fields get rebuilt/wiped below.
        document.querySelectorAll('#ceTestOverrides [data-override-path]').forEach(input => {
            if (input.value.trim() !== '') persistedTestOverrides[input.dataset.overridePath] = input.value;
        });

        const container = document.getElementById('ceTestOverrides');
        container.replaceChildren();
        for (const path of catalog.contextPaths) {
            const cell = document.createElement('div');
            const label = document.createElement('label');
            label.textContent = path;
            label.style.cssText = 'display:block; font-family:monospace; font-size:.75em; color:var(--text-muted); margin-bottom:.15rem;';
            const input = document.createElement('input');
            input.type = 'text';
            input.className = 'form-input';
            input.dataset.overridePath = path;
            input.style.width = '100%';
            input.value = persistedTestOverrides[path] || '';
            cell.appendChild(label);
            cell.appendChild(input);
            container.appendChild(cell);
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

    function applyTestOverrideValues(values) {
        document.querySelectorAll('#ceTestOverrides [data-override-path]').forEach(input => {
            const value = values[input.dataset.overridePath];
            if (value !== undefined) input.value = value;
        });
    }

    // "Import a game from IGDB" search box above the test-override fields — picking a
    // result pre-fills every game#* field this endpoint can resolve (see
    // ChatCommandsDslIgdbPreviewHandler), instead of typing each one by hand.
    let igdbSearchTimer = null;

    function setupIgdbSearch() {
        const input = document.getElementById('ceIgdbSearch');
        const results = document.getElementById('ceIgdbResults');
        if (!input || !results) return;

        function renderResults(games) {
            results.replaceChildren();
            if (!games.length) { results.style.display = 'none'; return; }
            games.forEach(game => {
                const li = document.createElement('li');
                const span = document.createElement('span');
                span.textContent = game.name;
                li.appendChild(span);
                li.addEventListener('click', async () => {
                    results.style.display = 'none';
                    input.value = game.name;
                    try {
                        const resp = await window.catapultWs.request('chat-commands.dsl.igdb-preview',
                            { id: game.id, name: game.name });
                        applyTestOverrideValues(resp.result || {});
                    } catch (err) {
                        reportEditorError(err);
                    }
                });
                results.appendChild(li);
            });
            results.style.display = 'block';
        }

        input.addEventListener('input', () => {
            clearTimeout(igdbSearchTimer);
            const q = input.value.trim();
            if (q.length < 2) { results.style.display = 'none'; return; }
            igdbSearchTimer = setTimeout(async () => {
                try {
                    const resp = await window.catapultWs.request('chat-commands.dsl.igdb-search', { q });
                    renderResults(resp.result || []);
                } catch (err) {
                    results.style.display = 'none';
                }
            }, 300);
        });

        document.addEventListener('click', e => {
            if (e.target !== input && !results.contains(e.target)) results.style.display = 'none';
        });
    }

    setupIgdbSearch();

    document.getElementById('ceTestBtn').onclick = async () => {
        if (!currentCmd) return;
        try {
            const payload = { id: currentCmd.id, overrides: collectTestOverrides() };
            // Send whichever in-progress (possibly unsaved) content is authoritative, so
            // Tester reflects what's currently in the editor, not just the last save —
            // the server checks these before falling back to the persisted ast/ejectedJs
            // (see ApiChatCommandTestController#resolveJs/#resolveAst).
            if (ejectedJs !== null) {
                payload.ejectedJs = ejectedJs;
            } else {
                payload.ast = JSON.stringify(await currentAst());
            }
            const resp = await window.catapultWs.request('chat-commands.test', payload);
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

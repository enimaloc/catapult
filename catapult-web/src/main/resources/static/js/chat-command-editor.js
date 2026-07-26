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

    // Options function bound with `this` as the field itself (Blockly's dropdown menuGenerator
    // contract) so it always includes whatever's currently selected, even a TW id the catalog no
    // longer reports (disabled/removed after this block was built) — never a value the field
    // can't accept, unlike a plain static option list.
    function knownTwOptions() {
        const current = this.getValue ? this.getValue() : null;
        const options = (catalog.knownTws || []).map(tw => [tw.label + ' (' + tw.id + ')', tw.id]);
        if (current && !options.some(([, value]) => value === current)) {
            options.push([current + ' (TW inconnu ou désactivé)', current]);
        }
        return options.length ? options : [['(aucun TW connu)', '']];
    }

    // JSON block definitions can't reference a JS function for a dropdown's options (JSON is
    // data-only — see the identical rationale on cmd_property_get_picker below), so ID starts as
    // a static placeholder and this extension swaps in a real function-generated FieldDropdown.
    Blockly.Extensions.register('cmd_tw_picker_options', function () {
        const block = this;
        const input = block.inputList.find(i => i.fieldRow.some(f => f.name === 'ID'));
        const index = input.fieldRow.findIndex(f => f.name === 'ID');
        input.removeField('ID');
        input.insertFieldAt(index, new Blockly.FieldDropdown(knownTwOptions), 'ID');
    });

    // Convenience for filling in tw#has(name)'s argument (or any other spot a TW id string is
    // needed) without having to know/type the raw internal id by hand. Deliberately produces the
    // exact same AST shape as cmd_literal_string ({type:'literal', valueType:'STRING'}) — this is
    // a smarter way to CREATE that literal, not a new node type, so it needs no parser/generator/
    // compiler changes and a saved command always parses back fine even without this block
    // existing. It also means this block is never reconstructed from a saved AST (nodeType is
    // null, and 'literal' nodes already resolve to cmd_literal_string in nodeToBlock) — reloading
    // a command that used this picker shows a plain text block with the chosen id already typed
    // in, which is what keeps this dropdown safe: it never has to round-trip a stale value.
    class CmdTwPickerBlock {
        static type = 'cmd_tw_picker';
        static nodeType = null;
        static category() { return 'TW'; }
        static definition() {
            return { type: this.type, message0: '%1',
                args0: [{ type: 'field_dropdown', name: 'ID', options: [['▾', '']] }],
                output: null, colour: 200, tooltip: 'Choisir un trigger warning connu',
                extensions: ['cmd_tw_picker_options'] };
        }
        static toNode(block) {
            return { type: 'literal', value: block.getFieldValue('ID'), valueType: 'STRING' };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.value, 'ID');
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
            return { type: this.type, message0: 'arg %1 défaut "%2"',
                args0: [
                    { type: 'field_number', name: 'INDEX', value: 0, min: 0, precision: 1 },
                    { type: 'field_input', name: 'DEFAULT', text: '' }
                ],
                output: null, colour: 200 };
        }
        static toNode(block) {
            const defaultValue = block.getFieldValue('DEFAULT');
            return { type: 'arg-get', index: block.getFieldValue('INDEX'),
                defaultValue: defaultValue === '' ? null : defaultValue };
        }
        static fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            block.setFieldValue(node.index, 'INDEX');
            block.setFieldValue(node.defaultValue || '', 'DEFAULT');
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
    // Feeds the PROPERTY_PICKER helper dropdown below, not PROPERTY itself.
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
    // field, re-invoking it fresh every time the dropdown opens — always reflects whatever is
    // currently wired into TARGET. Only ever offers keys that are actually known right now, so
    // whatever the user picks is guaranteed to be a valid option — unlike the reverted approach,
    // this dropdown's OWN value is never what's persisted (see the extension below), so there's
    // nothing for Blockly's option-membership validation to ever have to reject.
    function propertyPickerOptions() {
        const block = this.getSourceBlock();
        const keys = block ? computeTargetKeys(block.getInputTargetBlock('TARGET')) : [];
        return keys.length ? keys.map(k => [k, k]) : [['(aucune suggestion)', '']];
    }

    // JSON block definitions can't reference a JS function for a dropdown's options (JSON is
    // data-only), so PROPERTY_PICKER is defined with a static placeholder and this extension
    // swaps it for a real function-generated FieldDropdown, then wires a validator that COPIES
    // whatever gets picked into the real PROPERTY field — PROPERTY_PICKER's own value is never
    // read by toNode()/fromNode() and is not part of the saved AST at all, it's a pure UI
    // trigger. This is what makes a dropdown safe here: PROPERTY (a plain field_input) always
    // accepts and persists any value exactly like before, so an unknown-shaped TARGET (a plain
    // variable, the common case) just means the picker has nothing to suggest — it never blocks
    // typing or loses a previously-saved property name the way a validated PROPERTY dropdown did.
    Blockly.Extensions.register('cmd_property_get_picker', function () {
        const block = this;
        const input = block.inputList.find(i => i.fieldRow.some(f => f.name === 'PROPERTY_PICKER'));
        const index = input.fieldRow.findIndex(f => f.name === 'PROPERTY_PICKER');
        input.removeField('PROPERTY_PICKER');
        const picker = new Blockly.FieldDropdown(propertyPickerOptions);
        input.insertFieldAt(index, picker, 'PROPERTY_PICKER');
        picker.setValidator(function (newValue) {
            if (newValue) block.setFieldValue(newValue, 'PROPERTY');
            return newValue;
        });
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
            return { type: this.type, message0: 'get %1 [ %2 ] %3',
                args0: [
                    { type: 'input_value', name: 'TARGET' },
                    { type: 'field_input', name: 'PROPERTY', text: 'name' },
                    { type: 'field_dropdown', name: 'PROPERTY_PICKER', options: [['▾', '']] }
                ],
                output: null, colour: 25,
                extensions: ['cmd_property_get_picker']
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

    // Fixed, known list sources DynamicChatCommand#resolveList actually recognizes — anything
    // else silently resolves to an empty list at runtime, so these are the only values worth
    // offering. Static (not catalog-derived), so the dropdown's own options never change.
    const KNOWN_LIST_SOURCES = ['fallbacks', 'args', 'ownCommands', 'gameDlcs', 'similarGames', 'activeTws'];

    function listSourcePickerOptions() {
        return KNOWN_LIST_SOURCES.map(name => [name, name]);
    }

    // Same rationale as cmd_property_get_picker below: LIST_SOURCE stays a plain field_input so
    // any previously-saved value (including one typed before this picker existed) always loads
    // and saves correctly, and LIST_SOURCE_PICKER is a pure suggestion whose own value is never
    // read by toNode() — picking one just copies it into LIST_SOURCE via this validator.
    Blockly.Extensions.register('cmd_for_each_list_source_picker', function () {
        const block = this;
        const input = block.inputList.find(i => i.fieldRow.some(f => f.name === 'LIST_SOURCE_PICKER'));
        const index = input.fieldRow.findIndex(f => f.name === 'LIST_SOURCE_PICKER');
        input.removeField('LIST_SOURCE_PICKER');
        const picker = new Blockly.FieldDropdown(listSourcePickerOptions);
        input.insertFieldAt(index, picker, 'LIST_SOURCE_PICKER');
        picker.setValidator(function (newValue) {
            if (newValue) block.setFieldValue(newValue, 'LIST_SOURCE');
            return newValue;
        });
    });

    class CmdForEachBlock {
        static type = 'cmd_for_each';
        static nodeType = 'for-each';
        static category() { return 'Contrôle'; }
        static definition() {
            return { type: this.type, message0: 'for each %1 in %2 %3 %4',
                args0: [
                    { type: 'field_input', name: 'BINDING', text: 'f' },
                    { type: 'field_input', name: 'LIST_SOURCE', text: 'fallbacks' },
                    { type: 'field_dropdown', name: 'LIST_SOURCE_PICKER', options: [['▾', '']] },
                    { type: 'input_statement', name: 'BODY' }
                ],
                previousStatement: null, nextStatement: null, colour: 120,
                extensions: ['cmd_for_each_list_source_picker'] };
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
    //
    // fn.isAction (ban/timeout/shoutout/sendMessage/setParam — always return "", exist only for
    // their side effect) renders as its own directly stackable STATEMENT block instead of a
    // value block that has to be plugged into some other block's socket (typically print) to do
    // anything — there's no new AST shape involved: it's still exactly the {type:"print",
    // expr:{type:"service-call",...}} the compiler already knows how to run (the compiled
    // __output += (...) picks up the guaranteed-"" result and appends nothing), only how it's
    // presented and reconstructed in the Blocks tab changes (see nodeToBlock's 'print' branch).
    class ServiceCallBlock {
        constructor(fn) {
            this.fn = fn;
            this.type = 'cmd_call_' + fn.namespace + '_' + fn.name;
        }
        category() { return namespaceCategory(this.fn.namespace); }
        definition() {
            // "?" suffix marks a parameter the streamer can safely leave unconnected — it still
            // compiles to a "" literal like any empty slot, but the label makes clear that's
            // fine here (see ServiceFunction#optionalParameterNames) instead of looking like a
            // half-filled-in block.
            const optional = new Set(this.fn.optionalParameterNames || []);
            const argRefs = this.fn.parameterNames
                .map((name, i) => (optional.has(name) ? name + '?' : name) + ': %' + (i + 1))
                .join(', ');
            return {
                type: this.type,
                message0: this.fn.namespace + '.' + this.fn.name + '(' + argRefs + ')',
                args0: this.fn.parameterNames.map((name, i) => ({ type: 'input_value', name: 'ARG' + i })),
                colour: 290,
                ...(this.fn.isAction ? { previousStatement: null, nextStatement: null } : { output: null })
            };
        }
        toNode(block) {
            const args = this.fn.parameterNames.map((name, i) => blockToNode(block.getInputTargetBlock('ARG' + i)));
            const call = { type: 'service-call', namespace: this.fn.namespace, function: this.fn.name, args: args };
            // An action's return value is never used — this is exactly the AST shape a bare
            // {ns#fn(args)} tag already produces in the text DSL (an implicit print whose result,
            // always "", contributes nothing to the output), just built directly here instead of
            // via a separate cmd_print block wrapping this one.
            return this.fn.isAction ? { type: 'print', expr: call } : call;
        }
        fromNode(ws, node) {
            const block = ws.newBlock(this.type);
            this.fn.parameterNames.forEach((name, i) => connectValue(ws, block, 'ARG' + i, node.args[i]));
            return block;
        }
    }

    const STATIC_BLOCK_CLASSES = [
        CmdLiteralStringBlock, CmdLiteralNumberBlock, CmdLiteralBooleanBlock, CmdTwPickerBlock,
        CmdVarRefBlock, CmdContextGetBlock, CmdSettingGetBlock, CmdArgGetBlock,
        CmdObjectLiteralBlock, CmdObjectPropertyBlock, CmdPropertyGetBlock,
        CmdVarDeclBlock, CmdAssignBlock, CmdConcatBlock, CmdPrintBlock, CmdIfBlock, CmdForEachBlock
    ];
    const TOOLBOX_CATEGORY_ORDER = ['Variables', 'Contrôle', 'Texte', 'Contexte'];
    const TOOLBOX_CATEGORY_COLOURS = { Variables: '20', 'Contrôle': '210', Texte: '60', Contexte: '200' };
    // Registered functions get their own toolbox category per namespace instead of one shared
    // "Fonctions" bucket, so a growing function catalog stays browsable (Twitch/Steam/Catapult/
    // IGDB, one tab each) instead of piling every service into a single flat list.
    const SERVICE_CATEGORY_DISPLAY_NAMES = { igdb: 'IGDB', tw: 'TW' };
    function namespaceCategory(namespace) {
        return SERVICE_CATEGORY_DISPLAY_NAMES[namespace]
            || (namespace.charAt(0).toUpperCase() + namespace.slice(1));
    }

    // blockly type -> class/instance implementing the contract above — the single dispatch
    // table for blockToNode(). AST-node-type -> class/instance for the reverse direction is
    // built inline in nodeToBlock() below (literal/service-call need special-casing there;
    // everything else is a 1:1 lookup via each class's static `nodeType`).
    let blockRegistry = {};

    // The single, fixed entry point of a command's script — not part of the toolbox (there's
    // only ever one per workspace, auto-placed rather than dragged in) and never appears in the
    // AST itself: blocksToAst() reads only what's connected below it, so stray blocks dropped
    // elsewhere in the canvas (experiments, copy-pasted fragments) are silently excluded from
    // what gets saved instead of being concatenated in alongside the real script by accident.
    const CMD_START_TYPE = 'cmd_start';

    function ensureStartBlockTypeDefined() {
        if (Blockly.Blocks[CMD_START_TYPE]) return;
        Blockly.defineBlocksWithJsonArray([{
            type: CMD_START_TYPE,
            message0: 'Début',
            nextStatement: null,
            colour: 0,
            tooltip: 'Point de départ de la commande — connecte les blocs de ta commande ici en dessous.'
        }]);
    }

    /** Finds the workspace's entry block, creating it (undeletable) if this is a fresh workspace. */
    function ensureEntryBlock(ws) {
        let entry = ws.getTopBlocks(false).find(b => b.type === CMD_START_TYPE);
        if (entry) return entry;
        entry = ws.newBlock(CMD_START_TYPE);
        entry.setDeletable(false);
        entry.initSvg();
        entry.render();
        entry.moveBy(20, 20);
        return entry;
    }

    function ensureBlocksDefined() {
        if (Object.keys(blockRegistry).length) return;
        const serviceCallBlocks = catalog.serviceFunctions.map(fn => new ServiceCallBlock(fn));
        const all = [...STATIC_BLOCK_CLASSES, ...serviceCallBlocks];
        all.forEach(b => { blockRegistry[b.type] = b; });
        Blockly.defineBlocksWithJsonArray(all.map(b => b.definition(catalog)));
        ensureStartBlockTypeDefined();
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
            theme: buildBlocklyTheme(),
            // Blockly defaults to loading zoom control icons from './media/' relative to the
            // current page URL, which never resolves correctly here since the editor is
            // injected on arbitrary app pages, not on a page served from the webjar itself —
            // without this, the zoom in/out/reset buttons render broken and don't work.
            media: '/webjars/blockly/media/',
            zoom: {
                controls: true,
                wheel: true,
                startScale: 1,
                maxScale: 3,
                minScale: 0.3,
                scaleSpeed: 1.2,
                pinch: true
            }
        });
        workspace.resize();
        ensureEntryBlock(workspace);
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
        // Only what's connected below the fixed entry block is part of the saved script —
        // anything else floating in the canvas (stray experiments, copy-pasted fragments not
        // reattached) is deliberately ignored rather than silently concatenated in.
        const ws = ensureWorkspace();
        const entry = ensureEntryBlock(ws);
        const statements = statementsToNodes(entry.getNextBlock());
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
        } else if (node.type === 'print' && node.expr && node.expr.type === 'service-call'
                && catalog.serviceFunctions.some(f => f.namespace === node.expr.namespace
                    && f.name === node.expr.function && f.isAction)) {
            // Collapse the implicit print-wrapper an action call compiles through (see
            // ServiceCallBlock) back into that action's own statement block, rather than
            // rendering it as a separate cmd_print block with the call nested in its EXPR socket.
            const fn = catalog.serviceFunctions.find(f => f.namespace === node.expr.namespace
                && f.name === node.expr.function);
            block = new ServiceCallBlock(fn).fromNode(ws, node.expr);
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
        let previous = ensureEntryBlock(ws);
        for (const node of (ast.statements || [])) {
            let block;
            try {
                block = nodeToBlock(ws, node);
            } catch (err) {
                console.warn('chat-command-editor: skipping statement in Blocks view', node, err);
                continue;
            }
            previous.nextConnection.connect(block.previousConnection);
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
            renderServiceMockFields();
            if (ejectedJs === null) astToBlocks(await textToAst(cmd.template || ''));
        } catch (err) {
            reportEditorError(err);
        }
    }

    function closeEditor() {
        document.getElementById('chatCommandEditorModal').hidden = true;
        document.getElementById('chatCommandEditorModal').style.display = 'none';
        document.getElementById('ceModalContent').classList.remove('ce-fullscreen');
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

    document.getElementById('ceFullscreenBtn').onclick = () => {
        document.getElementById('ceModalContent').classList.toggle('ce-fullscreen');
        if (workspace) workspace.resize();
    };

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
    // testing one command are still there when they open a different one — the same mock rig is
    // usually reused across several commands in a row, not re-typed for each. Keyed by
    // "namespace#function" for a scalar-returning function, or "namespace#function::field" for
    // one return-key of a DTO-returning function.
    let persistedServiceMocks = {};

    // One labelled input per non-action service function (steam#getGame, catapult#getCurrentGame,
    // tw#has, ...) — actions (ban/timeout/...) always return "" so mocking them wouldn't do
    // anything. A DTO-returning function (fn.returnKeys non-empty) gets one input per field
    // instead of a single JSON blob, so a streamer edits e.g. steam#getGame's short_description
    // directly rather than hand-writing {"short_description":"..."}.
    function renderServiceMockFields() {
        document.querySelectorAll('#ceServiceMocks [data-mock-key]').forEach(input => {
            if (input.value.trim() !== '') persistedServiceMocks[input.dataset.mockKey] = input.value;
        });

        const container = document.getElementById('ceServiceMocks');
        container.replaceChildren();
        for (const fn of catalog.serviceFunctions) {
            if (fn.isAction) continue;
            const fnKey = fn.namespace + '#' + fn.name;
            const group = document.createElement('div');
            group.style.cssText = 'margin-bottom:.75rem;';
            const heading = document.createElement('div');
            heading.textContent = fnKey;
            heading.style.cssText = 'font-family:monospace; font-size:.8em; color:var(--text); margin-bottom:.25rem;';
            group.appendChild(heading);

            const grid = document.createElement('div');
            grid.style.cssText = 'display:grid; grid-template-columns:repeat(auto-fill, minmax(220px, 1fr)); gap:.35rem .75rem;';
            const fields = fn.returnKeys && fn.returnKeys.length ? fn.returnKeys : [null];
            for (const field of fields) {
                const mockKey = field === null ? fnKey : fnKey + '::' + field;
                const cell = document.createElement('div');
                const label = document.createElement('label');
                label.textContent = field === null ? '(valeur retournée)' : field;
                label.style.cssText = 'display:block; font-family:monospace; font-size:.75em; color:var(--text-muted); margin-bottom:.15rem;';
                const input = document.createElement('input');
                input.type = 'text';
                input.className = 'form-input';
                input.dataset.mockKey = mockKey;
                input.dataset.mockFunction = fnKey;
                if (field !== null) input.dataset.mockField = field;
                input.style.width = '100%';
                input.value = persistedServiceMocks[mockKey] || '';
                cell.appendChild(label);
                cell.appendChild(input);
                grid.appendChild(cell);
            }
            group.appendChild(grid);
            container.appendChild(group);
        }
    }

    // Groups per-field inputs back into one mock value per function: a DTO-returning function's
    // fields are combined into a JSON object string (server parses it back into a map via
    // MockingServiceFunctionRegistry), a scalar one sends its single field's raw text as-is.
    function collectServiceMocks() {
        const mocks = {};
        const byFunction = {};
        document.querySelectorAll('#ceServiceMocks [data-mock-key]').forEach(input => {
            const fnKey = input.dataset.mockFunction;
            (byFunction[fnKey] = byFunction[fnKey] || []).push(input);
        });
        for (const [fnKey, inputs] of Object.entries(byFunction)) {
            if (inputs.length === 1 && inputs[0].dataset.mockField === undefined) {
                const value = inputs[0].value.trim();
                if (value !== '') mocks[fnKey] = value;
                continue;
            }
            const obj = {};
            let any = false;
            inputs.forEach(input => {
                const value = input.value.trim();
                if (value !== '') { obj[input.dataset.mockField] = value; any = true; }
            });
            if (any) mocks[fnKey] = JSON.stringify(obj);
        }
        return mocks;
    }

    document.getElementById('ceTestBtn').onclick = async () => {
        if (!currentCmd) return;
        try {
            const payload = { id: currentCmd.id, overrides: {}, serviceMocks: collectServiceMocks() };
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

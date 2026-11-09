/**
 * A saved custom binding's schedule, in its row's form on the channel page (channel-custom-bindings.js wires it with
 * the row, and keeps its category in step).
 *
 * - The slots are the section's rows, their data-* the slot as saved (startsAt/endsAt wall time of its timeZone,
 *   repeat, days, repeatUntil, paused, suspended, nextStart). Every change (a pause switched, a slot deleted, the
 *   editor saved) posts the whole list to the section's data-save-url, with the ids of the slots it was edited from
 *   (known: refused as stale when they changed elsewhere meanwhile, and the page is then reloaded), and shows the
 *   list the answer gives back, in the page's words: the
 *   summary built as the template does, from the section's data-format-* patterns and data-label-*, the day names from
 *   the editor's chips. A refusal is told through CatapultChannel.showError, in the section's data-error-* text for its
 *   code (refusalText); a switch that failed goes back.
 * - "Add a slot" is offered on a premium account (data-can-repeat) or on the free category (data-free-game-id) only,
 *   while the binding has fewer than data-max-slots; repeating is disabled in the editor by the template when the
 *   account can't.
 * - Slots are shown and typed in the browser's zone: one saved from another zone is said in this one (its days and end
 *   date moved with its start), and a slot saved from the editor takes the browser's zone. One not edited is posted
 *   back as it was saved. The server says the slots in their own zone: said again when the browser's differs.
 * - "Next activation" is the earliest nextStart of the slots neither paused nor suspended, in the browser's zone.
 *
 * Every text goes in through textContent or a property, never as markup.
 */
window.CatapultCustomBindingSchedules = (function () {
    const DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
    const show = (el, visible) => el?.classList.toggle("hidden", !visible);
    const wired = new WeakSet();
    const lang = () => document.documentElement.lang || undefined;
    const sectionOf = item => item?.querySelector(".custom-binding-schedules") ?? null;

    /** "2026-11-10T20:00[:00]" or "2026-11-10": its fields. */
    function local(value) {
        const m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(value ?? "");
        if (!m) return null;
        return { y: +m[1], M: +m[2], d: +m[3], H: +(m[4] ?? 0), m: +(m[5] ?? 0) };
    }

    /** An instant's wall time in `zone`. */
    function zoned(instant, zone) {
        const date = new Date(instant);
        if (Number.isNaN(date.getTime())) return null;
        const parts = {};
        new Intl.DateTimeFormat("en-US", {
            timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit", hourCycle: "h23"
        }).formatToParts(date).forEach(p => { parts[p.type] = +p.value; });
        return { y: parts.year, M: parts.month, d: parts.day, H: parts.hour % 24, m: parts.minute };
    }

    const pad = n => String(n).padStart(2, "0");

    /** The IANA zone the browser shows times in (the section's, the server's default, when unknown). */
    function browserZone(section) {
        try {
            return Intl.DateTimeFormat().resolvedOptions().timeZone || section.dataset.zone;
        } catch {
            return section.dataset.zone;
        }
    }

    const dayNumber = t => Date.UTC(t.y, t.M - 1, t.d) / 86_400_000;
    const iso = t => `${t.y}-${pad(t.M)}-${pad(t.d)}`;
    const isoTime = t => `${iso(t)}T${pad(t.H)}:${pad(t.m)}:00`;

    /** The instant `t` (wall time) is in `zone`: the offset is read where the guess lands, twice for a DST change. */
    function instant(t, zone) {
        const wall = Date.UTC(t.y, t.M - 1, t.d, t.H, t.m);
        let guess = wall;
        for (let i = 0; i < 2; i++) {
            const seen = zoned(guess, zone);
            guess = wall - (Date.UTC(seen.y, seen.M - 1, seen.d, seen.H, seen.m) - guess);
        }
        return guess;
    }

    /** `slot` in the browser's zone: as saved when it was saved there, else moved, its days and end date with it. */
    function shown(section, slot) {
        const from = slot.timeZone || section.dataset.zone;
        const to = browserZone(section);
        const start = local(slot.startsAt);
        const end = local(slot.endsAt);
        if (from === to || !start || !end) return slot;
        const movedStart = zoned(instant(start, from), to);
        const movedEnd = zoned(instant(end, from), to);
        const shift = dayNumber(movedStart) - dayNumber(start);
        const until = local(slot.repeatUntil);
        return {
            ...slot,
            startsAt: isoTime(movedStart),
            endsAt: isoTime(movedEnd),
            days: (slot.days ?? []).map(day => DAYS[(DAYS.indexOf(day) + shift + 7) % 7]),
            repeatUntil: until ? iso(zoned(Date.UTC(until.y, until.M - 1, until.d + shift), "UTC")) : slot.repeatUntil,
        };
    }

    /** `t` in a DateTimeFormatter `pattern` (the letters the bundles use: y M d E H h m a, 'quoted' text). */
    function format(t, pattern) {
        if (!t) return "";
        const utc = new Date(Date.UTC(t.y, t.M - 1, t.d));
        const name = options => new Intl.DateTimeFormat(lang(), { timeZone: "UTC", ...options }).format(utc);
        return pattern.replace(/'([^']*)'|y+|M+|d+|E+|H+|h+|m+|a/g, (token, quoted) => {
            if (quoted !== undefined) return quoted;
            switch (token[0]) {
                case "y": return String(t.y);
                case "M": return token.length >= 4 ? name({ month: "long" }) : token.length === 2 ? pad(t.M) : String(t.M);
                case "d": return token.length === 2 ? pad(t.d) : String(t.d);
                case "E": return name({ weekday: token.length >= 4 ? "long" : "short" });
                case "H": return token.length === 2 ? pad(t.H) : String(t.H);
                case "h": return String(t.H % 12 || 12);
                case "m": return pad(t.m);
                default: return t.H < 12 ? "AM" : "PM";
            }
        });
    }

    /** A slot in words, as the template says it, in the browser's zone. */
    function summary(section, saved) {
        const data = section.dataset;
        const slot = shown(section, saved);
        if (slot.repeat !== "DAILY" && slot.repeat !== "WEEKLY") {
            return `${format(local(slot.startsAt), data.formatDayTime)} → ${format(local(slot.endsAt), data.formatDayTime)}`;
        }
        const times = `${format(local(slot.startsAt), data.formatTime)} → ${format(local(slot.endsAt), data.formatTime)}`;
        const until = slot.repeatUntil
            ? ` · ${(data.labelUntil ?? "{0}").replace("{0}", format(local(slot.repeatUntil), data.formatDate))}` : "";
        if (slot.repeat === "DAILY") return `${times} · ${data.labelDaily}${until}`;
        const names = new Map(Array.from(section.querySelectorAll(".custom-binding-schedule-day"),
            chip => [chip.getAttribute("value"), chip.dataset.name]));
        const days = DAYS.filter(day => (slot.days ?? []).includes(day)).map(day => names.get(day) ?? day).join(", ");
        return `${days.charAt(0).toUpperCase()}${days.slice(1)} · ${times} · ${data.labelWeekly}${until}`;
    }

    /** The slot a row shows, as saved. */
    function slotOf(row) {
        const data = row.dataset;
        return {
            id: data.scheduleId || null,
            startsAt: data.startsAt ?? null,
            endsAt: data.endsAt ?? null,
            repeat: data.repeat || "NONE",
            days: data.days ? data.days.split(",") : [],
            repeatUntil: data.repeatUntil || null,
            timeZone: data.timeZone || null,
            paused: data.paused === "true",
            suspended: data.suspended === "true",
            nextStart: data.nextStart || null,
        };
    }

    const rows = section => Array.from(section.querySelectorAll(".custom-binding-schedule-list .custom-binding-schedule"));

    /** What is posted for a slot: catapult-api works the rest out. */
    function request(slot) {
        const { id, startsAt, endsAt, repeat, days, repeatUntil, timeZone, paused } = slot;
        return { id, startsAt, endsAt, repeat, days, repeatUntil, timeZone, paused };
    }

    /** Brings the section to `slots` (the DTOs catapult-api answers), its next activation with them. */
    function render(section, slots) {
        const list = section.querySelector(".custom-binding-schedule-list");
        const template = document.getElementById("custom-binding-schedule-template");
        if (!list || !template) return;
        list.replaceChildren();
        (slots ?? []).forEach(slot => {
            const row = template.content.firstElementChild.cloneNode(true);
            const data = row.dataset;
            data.scheduleId = slot.id ?? "";
            data.startsAt = slot.startsAt ?? "";
            data.endsAt = slot.endsAt ?? "";
            data.repeat = slot.repeat ?? "NONE";
            data.days = (slot.days ?? []).join(",");
            if (slot.repeatUntil) data.repeatUntil = slot.repeatUntil;
            if (slot.timeZone) data.timeZone = slot.timeZone;
            data.paused = String(!!slot.paused);
            data.suspended = String(!!slot.suspended);
            if (slot.nextStart) data.nextStart = slot.nextStart;
            row.querySelector(".custom-binding-schedule-summary").textContent = summary(section, slot);
            show(row.querySelector(".custom-binding-schedule-suspended"), !!slot.suspended);
            list.appendChild(row);
            // Once in the page, the switch and buttons are upgraded: their properties are theirs.
            row.querySelector(".custom-binding-schedule-pause").checked = !slot.paused;
            row.querySelector(".custom-binding-schedule-edit").disabled = !!slot.suspended;
            wireRow(section, row);
        });
        show(section.querySelector(".custom-binding-schedule-empty"), !(slots ?? []).length);
        showNext(section);
        showAdd(section);
    }

    function showNext(section) {
        const next = section.querySelector(".custom-binding-schedule-next");
        if (!next) return;
        const due = rows(section).map(slotOf).filter(slot => !slot.paused && !slot.suspended && slot.nextStart)
            .map(slot => slot.nextStart).sort((a, b) => Date.parse(a) - Date.parse(b));
        const when = due.length ? format(zoned(due[0], browserZone(section)), section.dataset.formatNext) : "";
        next.textContent = when ? (section.dataset.labelNext ?? "{0}").replace("{0}", when) : "";
        show(next, !!when);
    }

    /** Whether the account may add a slot to the binding: premium, or the free category. */
    function canAdd(section) {
        const data = section.dataset;
        return data.canRepeat === "true" || (!!data.gameId && data.gameId === data.freeGameId);
    }

    function showAdd(section) {
        const allowed = canAdd(section);
        const editing = !section.querySelector(".custom-binding-schedule-editor")?.classList.contains("hidden");
        const max = Number(section.dataset.maxSlots);
        const full = max > 0 && rows(section).length >= max;
        show(section.querySelector(".custom-binding-schedule-add"), allowed && !editing && !full);
        show(section.querySelector(".custom-binding-schedule-add-unavailable"), !allowed);
        show(section.querySelector(".custom-binding-schedule-add-premium"), !allowed);
    }

    /**
     * Posts the section's whole list with `slots` in its place and shows the answer. False (told) when it was
     * refused or could not be sent: the section is left as it was.
     */
    async function save(section, slots) {
        const url = section.dataset.saveUrl;
        if (!url || section.dataset.pending === "true") return false;
        section.dataset.pending = "true";
        try {
            const bindings = window.CatapultCustomBindings;
            const known = rows(section).map(row => row.dataset.scheduleId).filter(Boolean);
            const response = await bindings.post(url, { known, slots: slots.map(request) });
            if (!response) return false;
            if (!response.ok) {
                const code = await bindings.readCode(response);
                CatapultChannel.showError(bindings.refusalText(section, code, response.status));
                // Saved elsewhere since this list was shown: shown again as it is
                if (code === "stale") CatapultChannel.refresh();
                return false;
            }
            let answer = null;
            try {
                answer = await response.json();
            } catch { /* nothing to show: kept as sent */ }
            render(section, Array.isArray(answer) ? answer : slots);
            return true;
        } finally {
            delete section.dataset.pending;
        }
    }

    function wireRow(section, row) {
        const pause = row.querySelector(".custom-binding-schedule-pause");
        pause?.addEventListener("change", async () => {
            const id = row.dataset.scheduleId;
            const paused = !pause.checked;
            const slots = rows(section).map(slotOf).map(slot => (slot.id === id ? { ...slot, paused } : slot));
            // Refused: back to what it was (on: scheduled).
            if (!(await save(section, slots)) && row.isConnected) pause.checked = paused;
        });
        row.querySelector(".custom-binding-schedule-edit")?.addEventListener("click", () => {
            if (row.dataset.suspended !== "true") openEditor(section, slotOf(row));
        });
        row.querySelector(".custom-binding-schedule-delete")?.addEventListener("click", () => {
            const id = row.dataset.scheduleId;
            save(section, rows(section).map(slotOf).filter(slot => slot.id !== id));
        });
    }

    const editorOf = section => section.querySelector(".custom-binding-schedule-editor");
    const value = el => el?.value ?? el?.getAttribute("value") ?? "";

    function showRepeat(editor) {
        const repeat = value(editor.querySelector(".custom-binding-schedule-repeat")) || "NONE";
        show(editor.querySelector(".custom-binding-schedule-weekly"), repeat === "WEEKLY");
        show(editor.querySelector(".custom-binding-schedule-until"), repeat !== "NONE");
    }

    /** Opens the editor on `saved` (null: a new one), in the browser's zone. */
    function openEditor(section, saved) {
        const editor = editorOf(section);
        if (!editor) return;
        const slot = saved && shown(section, saved);
        editor.dataset.editing = slot?.id ?? "";
        editor.dataset.paused = String(!!slot?.paused);
        editor.querySelector(".custom-binding-schedule-start").value = (slot?.startsAt ?? "").slice(0, 16);
        editor.querySelector(".custom-binding-schedule-end").value = (slot?.endsAt ?? "").slice(0, 16);
        editor.querySelector(".custom-binding-schedule-repeat").value = slot?.repeat ?? "NONE";
        editor.querySelectorAll(".custom-binding-schedule-day").forEach(chip => {
            chip.selected = (slot?.days ?? []).includes(chip.getAttribute("value"));
        });
        editor.querySelector(".custom-binding-schedule-until").value = slot?.repeatUntil ?? "";
        showRepeat(editor);
        show(editor, true);
        showAdd(section);
        editor.querySelector(".custom-binding-schedule-start")?.focus?.();
    }

    function closeEditor(section) {
        show(editorOf(section), false);
        showAdd(section);
    }

    /** A datetime-local's "2026-11-10T20:00" as the LocalDateTime catapult-api reads. */
    const seconds = v => (v && v.length === 16 ? `${v}:00` : v || null);

    async function saveEditor(section) {
        const editor = editorOf(section);
        const startsAt = seconds(value(editor.querySelector(".custom-binding-schedule-start")));
        const endsAt = seconds(value(editor.querySelector(".custom-binding-schedule-end")));
        if (!startsAt || !endsAt) return CatapultChannel.showError(section.dataset.errorInvalid);
        const repeat = value(editor.querySelector(".custom-binding-schedule-repeat")) || "NONE";
        const days = repeat === "WEEKLY"
            ? Array.from(editor.querySelectorAll(".custom-binding-schedule-day"))
                .filter(chip => chip.selected).map(chip => chip.getAttribute("value"))
            : [];
        const until = value(editor.querySelector(".custom-binding-schedule-until"));
        const slot = {
            id: editor.dataset.editing || null, startsAt, endsAt, repeat, days,
            repeatUntil: repeat !== "NONE" && until ? until : null, timeZone: browserZone(section),
            paused: editor.dataset.paused === "true"
        };
        const current = rows(section).map(slotOf);
        const slots = slot.id ? current.map(s => (s.id === slot.id ? slot : s)) : [...current, slot];
        if (await save(section, slots)) closeEditor(section);
    }

    /** Wires the schedule of a binding's row (`item`), when it has one. */
    function attach(item) {
        const section = sectionOf(item);
        if (!section || wired.has(section)) return;
        wired.add(section);
        rows(section).forEach(row => wireRow(section, row));
        const zone = browserZone(section);
        if (zone !== section.dataset.zone || rows(section).some(row => (row.dataset.timeZone || zone) !== zone)) {
            rows(section).forEach(row => {
                row.querySelector(".custom-binding-schedule-summary").textContent = summary(section, slotOf(row));
            });
            showNext(section);
        }
        const editor = editorOf(section);
        section.querySelector(".custom-binding-schedule-add")?.addEventListener("click", () => openEditor(section, null));
        editor?.querySelector(".custom-binding-schedule-repeat")?.addEventListener("change", () => showRepeat(editor));
        editor?.querySelector(".custom-binding-schedule-cancel")?.addEventListener("click", () => closeEditor(section));
        editor?.querySelector(".custom-binding-schedule-save")?.addEventListener("click", () => saveEditor(section));
        showAdd(section);
    }

    /** A row template's copy gets its binding: the route its schedule is saved to, and its category. */
    function enable(item, url, gameId) {
        const section = sectionOf(item);
        if (!section) return;
        section.dataset.saveUrl = url;
        setGame(item, gameId);
    }

    /** The binding's saved category changed: adding a slot may be offered, or not any more. */
    function setGame(item, gameId) {
        const section = sectionOf(item);
        if (!section) return;
        if (gameId) section.dataset.gameId = gameId;
        else delete section.dataset.gameId;
        showAdd(section);
    }

    /** Shows `slots` (DTOs) in a binding's row, unless its editor is open: the owner may be typing. */
    function showSlots(item, slots) {
        const section = sectionOf(item);
        if (!section || !editorOf(section)?.classList.contains("hidden")) return;
        render(section, slots);
    }

    return { attach, enable, setGame, show: showSlots };
})();

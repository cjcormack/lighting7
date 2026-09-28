import { Component, createElement, createRef } from "react";

// Callbacks the widget itself invokes and this wrapper forwards untouched.
//
// `onChange` is deliberately NOT one of them. The widget debounces it 500 ms with no maxWait and
// no flush, so every consumer's copy of the body trailed the editor — type-then-Escape closed a
// sheet with no "Discard changes?" and lost the tail, and a Compile/Run/Save inside the window
// sent the previous text. This wrapper listens to CodeMirror directly instead (see
// `handleCodemirrorChange`), which reports on the keystroke.
const EVENTS = [
    "onConsoleOpen",
    "onConsoleClose",
    "getInstance",
    "getJsCode",
    "onRun",
    "onError",
];

// Props this wrapper consumes itself: neither forwarded to the widget as event functions nor
// rendered as attributes on the `<code>` element.
const WRAPPER_PROPS = [
    "className",
    "playground",
    "onChange",
    "onInitFailure",
    "getEditor",
];

// One source of truth for "this prop is not a DOM attribute", so a prop added to either list
// above cannot end up rendered onto the `<code>` element by being missed from a second check.
const NON_ATTRIBUTE_PROPS = new Set([...EVENTS, ...WRAPPER_PROPS]);

const DATA_ATTRS = [
    "version",
    "targetPlatform",
    "highlightOnly",
    "jsLibs",
    "minCompilerVersion",
    "autocomplete",
    "outputHeight",
    "trackRunId",
    "crosslink",
    "shorterHeight",
];

const INITED_ATTRIBUTE = "data-kotlin-playground-initialized";

function upper2dash(str) {
    return str.replace(/[A-Z]/g, "-$&").toLowerCase();
}

function normalizeAttribute(name) {
    let attr = name;
    if (DATA_ATTRS.indexOf(name) !== -1) attr = "data-" + attr;
    return upper2dash(attr);
}

class ReactKotlinPlayground extends Component {
    constructor(props, ...args) {
        super(props, ...args);

        this.code = createRef();

        // The `ExecutableFragment` the widget hands back through `getInstance` — the live editor.
        // Its `codemirror` is a placeholder until the widget has finished constructing, so
        // nothing reads it before the `playground()` promise has resolved.
        this.instance = null;

        // The `ExecutableCode` that promise resolves to. It, and not the fragment, owns
        // `destroy()` — the one that also un-hides the target node and clears the initialized
        // attribute, so the same node can be mounted again afterwards.
        this.executable = null;

        this.initializing = false;
        this.unmounted = false;

        // The body this wrapper last wrote into the editor itself. CodeMirror reports a
        // programmatic `setValue` as a change like any other, so without this a controlled write
        // (ScriptForm's Reset) would come straight back out as a user edit and undo itself.
        this.writtenCode = null;

        this.handleCodemirrorChange = this.handleCodemirrorChange.bind(this);

        // Handed to `getEditor` once the editor is live. Built once so a consumer holding it in a
        // ref does not have to care about re-renders.
        this.editor = {
            setBody: (code) => this.setBody(code),
        };

        EVENTS.forEach((event) => {
            if (!this[event]) this[event] = this.createProxy(event);
        });
    }

    createProxy(name) {
        return (...args) => {
            if (this.props[name]) this.props[name](...args);
        };
    }

    initPlayground() {
        if (this.unmounted || this.initializing || this.executable) return;

        const node = this.code.current;
        if (!node || !this.props.playground) return;
        if (node.getAttribute(INITED_ATTRIBUTE) === "true") return;

        const eventFunctions = EVENTS.reduce((events, name) => {
            events[name] = this[name] || this.props[name];
            return events;
        }, {});

        // Highlighting and completion are served by lighting7 itself (routes/scriptEditor.kt),
        // from the same embedded Kotlin compiler that runs the scripts. There used to be a
        // bundled kotlin-compiler-server here, on its own port, in its own JVM.
        //
        // This must match where the backend mounts the subtree. A mismatch used to be entirely
        // silent: the widget fetches `/versions` once per page and, on any failure, drops every
        // editor on the page to read-only with highlighting off, logging only to the console.
        // `onPlaygroundCreated` below is what turns that into something the operator can see.
        eventFunctions.server = "/api/script-editor";

        // The widget's hand-back of the live editor. This records it *and* forwards to whatever
        // the caller passed, rather than replacing it: it used to assign a
        // `window.playgroundInstance` global that nothing read, and clobber the caller's own
        // `getInstance` on the way, which is why no consumer could reach the editor.
        eventFunctions.getInstance = (instance) => {
            this.instance = instance;
            if (this.props.getInstance) this.props.getInstance(instance);
        };

        this.initializing = true;
        Promise.resolve(this.props.playground(node, eventFunctions)).then(
            (instances) => this.onPlaygroundCreated(instances),
            (error) => this.onPlaygroundFailed(error),
        );
    }

    onPlaygroundCreated(instances) {
        this.initializing = false;
        this.executable = Array.isArray(instances) ? instances[0] : null;

        if (this.unmounted) {
            this.destroyPlayground();
            return;
        }

        // The widget resolves with an instance whether or not its version probe succeeded: on a
        // failed `/versions` it logs to the console and builds a highlight-only editor *without*
        // the event functions. So a genuinely live editor is exactly one that called
        // `getInstance` back; anything else is that fallback, and without this the operator is
        // left typing into an editor that silently refuses every keystroke.
        const codemirror = this.instance && this.instance.codemirror;
        if (!this.executable || !codemirror) {
            this.reportInitFailure();
            return;
        }

        try {
            codemirror.on("change", this.handleCodemirrorChange);
            if (this.props.getEditor) this.props.getEditor(this.editor);
        } catch (error) {
            // A throw here is inside a fulfillment handler, so the `onRejected` arm below would
            // not see it — it would become an unhandled rejection and leave a normal-looking
            // editor that never reports a keystroke, which is the exact failure this reports on.
            this.reportInitFailure(error);
        }
    }

    onPlaygroundFailed(error) {
        this.initializing = false;
        if (this.unmounted) return;
        this.reportInitFailure(error);
    }

    reportInitFailure(error) {
        if (this.props.onInitFailure) this.props.onInitFailure(error);
    }

    handleCodemirrorChange(codemirror) {
        const code = codemirror.getValue();
        if (code === this.writtenCode) return;
        this.writtenCode = null;
        if (this.props.onChange) this.props.onChange(code);
    }

    /**
     * Write `code` into a live editor without it echoing back out as a user edit.
     *
     * The body, not the wrapped source: while the fragment is folded — which it is, because the
     * caller hands it `//sampleStart` / `//sampleEnd` markers — CodeMirror holds exactly what
     * `onChange` reports, and the widget puts the markers back around it on the way to the server.
     */
    setBody(code) {
        const codemirror = this.instance && this.instance.codemirror;
        if (!codemirror || codemirror.getValue() === code) return;
        const cursor = codemirror.getCursor();
        this.writtenCode = code;
        codemirror.setValue(code);
        // A write-through corrects the value; it is not a navigation, so put the caret back.
        // `setCursor` clamps into the new document, so a shorter body is not a problem.
        codemirror.setCursor(cursor);
    }

    /** Tears the widget down. Safe to call twice, and after React has already unmounted us. */
    destroyPlayground() {
        const executable = this.executable;

        // Nothing has been built yet, so there is nothing here to take apart — and in particular
        // `instance` must be left alone, because an init still in flight is what will fill it in.
        // `unmounted` is what carries a teardown across to `onPlaygroundCreated` in that case.
        if (!executable) return;

        const codemirror = this.instance && this.instance.codemirror;
        this.executable = null;
        this.instance = null;
        this.writtenCode = null;

        if (codemirror) codemirror.off("change", this.handleCodemirrorChange);
        if (this.props.getEditor) this.props.getEditor(null);

        try {
            executable.destroy();
        } catch {
            // A widget that failed part-way through construction can throw from here. There is
            // nothing useful to do about it, and it must not take the unmount down with it.
        }
    }

    componentDidMount() {
        // Cleared rather than assumed: StrictMode simulates an unmount and a remount on this same
        // instance in development, so `unmounted` has to be a statement about now and not a latch
        // that leaves the editor permanently unmountable after the rehearsal.
        this.unmounted = false;
        this.initPlayground();
    }

    componentDidUpdate() {
        this.initPlayground();
    }

    componentWillUnmount() {
        this.unmounted = true;
        // If the promise is still in flight there is nothing to destroy yet; `onPlaygroundCreated`
        // sees `unmounted` and destroys the instance on arrival instead.
        this.destroyPlayground();
    }

    render() {
        const { className } = this.props;

        const elementProps = Object.keys(this.props).reduce((result, name) => {
            if (!NON_ATTRIBUTE_PROPS.has(name))
                result[normalizeAttribute(name)] = this.props[name];
            return result;
        }, {});

        return createElement(
            "div",
            { className },
            createElement("code", { ...elementProps, ref: this.code }, this.props.value),
        );
    }
}

export default ReactKotlinPlayground;

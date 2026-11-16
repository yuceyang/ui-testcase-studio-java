package com.yuceyang.uitest.ui;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 功能点树：自绘层级（Root 为 “/”、子级带引导线、勾选框紧贴标题），
 * 支持 Ctrl 点选 / Shift 连选 / 拖拽换父级。用自绘而非 TreeGrid，
 * 是因为 TreeGrid 无法同时满足引导线、紧凑勾选框与拖放移动。
 */
public class FunctionTree extends Div {
    /**
     * checked = 勾选框是否打勾；selected = 这一行是否高亮（当前生效的选法）。
     * 两者必须分开：点选/ Ctrl 选中的行不该把勾选框也打上勾。
     */
    public record Row(String id, String name, String badge, String kind, String title,
                      boolean hasChildren, boolean expanded, boolean checked, boolean selected,
                      boolean indeterminate, boolean synthetic, int count) {}

    public interface Handler {
        void toggle(String id);
        void select(String id, boolean ctrl, boolean shift);
        /** 勾选框：级联勾选/取消自身与所有子孙，并回推父级状态。 */
        void check(String id);
        void open(String id);
        void move(String draggedId, String targetId);
        /** 拖放：zone = before / after 表示在同一父级里排序，into 表示挂到目标节点下面。 */
        default void reorder(String draggedId, String targetId, String zone) { move(draggedId, targetId); }
        /** 右键菜单：id 为节点/用例行，x、y 为鼠标位置（客户端像素）。 */
        default void menu(String id, double x, double y) { }
        /** 右键菜单里点中某项：action 由菜单数据定义。 */
        default void menuPick(String id, String action) { }
    }

    private final Handler handler;
    /** 已渲染行的 DOM 节点：勾选/点选时只改这些行的状态，不重建整棵树。 */
    private final Map<String, Element> rowElements = new HashMap<>();
    private final Map<String, Element> checkElements = new HashMap<>();
    /** 上一次下发的状态：只给真正变化的行发属性更新。 */
    private final Map<String, Boolean> checkedState = new HashMap<>();
    private final Map<String, Boolean> halfState = new HashMap<>();
    private final Set<String> activeState = new HashSet<>();

    public FunctionTree(Handler handler) {
        this.handler = handler;
        addClassName("ft");
        getElement().setAttribute("role", "tree");
        installClientEvents();
    }

    public void render(List<Row> roots, Map<String, List<Row>> children) {
        removeAll();
        rowElements.clear();
        checkElements.clear();
        checkedState.clear();
        halfState.clear();
        activeState.clear();
        Div body = new Div();
        body.addClassName("ft-children");
        body.addClassName("ft-root");
        renderLevel(body, roots, children);
        add(body);
    }

    /**
     * 只更新已有行的勾选框与高亮，不重建 DOM。
     * 1300+ 行的树每点一下勾选框就整棵重建，会让浏览器卡一下并弹出加载进度条；
     * 勾选只是一组行状态在变，没必要拆掉重做。
     *
     * @param checkedIds 打勾的行（含 "ft-root" 表示整棵全选）
     * @param halfIds    半选的行
     * @param activeIds  当前高亮的行（勾选模式下=勾选集，点选模式下=点选集）
     */
    public void applyStates(Set<String> checkedIds, Set<String> halfIds, Set<String> activeIds) {
        for (Map.Entry<String, Element> entry : rowElements.entrySet()) {
            String id = entry.getKey();
            Element line = entry.getValue();
            boolean active = activeIds.contains(id);
            if (active != activeState.contains(id)) {
                if (active) line.getClassList().add("selected");
                else line.getClassList().remove("selected");
            }
            Element box = checkElements.get(id);
            if (box == null) continue;
            boolean checked = checkedIds.contains(id);
            if (!Boolean.valueOf(checked).equals(checkedState.get(id))) {
                box.setProperty("checked", checked);
                checkedState.put(id, checked);
            }
            boolean half = halfIds.contains(id);
            if (!Boolean.valueOf(half).equals(halfState.get(id))) {
                box.setProperty("indeterminate", half);
                halfState.put(id, half);
            }
        }
        activeState.clear();
        activeState.addAll(activeIds);
    }

    private void renderLevel(Div parent, List<Row> rows, Map<String, List<Row>> children) {
        for (Row row : rows) {
            Div node = new Div();
            node.addClassName("ft-node");
            Div line = new Div();
            line.addClassName("ft-row");
            line.getElement().setAttribute("data-id", row.id());
            if (row.synthetic()) line.addClassName("synthetic");
            if (row.selected()) line.addClassName("selected");
            if (row.kind() != null) line.getElement().setAttribute("data-kind", row.kind());
            if (row.title() != null) line.getElement().setAttribute("title", row.title());

            Span chevron = new Span(row.hasChildren() ? (row.expanded() ? "▾" : "▸") : "");
            chevron.addClassName("ft-chevron");
            line.add(chevron);

            Element check = new Element("input");
            check.setAttribute("type", "checkbox");
            check.setAttribute("class", "ft-check");
            check.setProperty("checked", row.checked());
            if (row.indeterminate()) check.setProperty("indeterminate", true);
            // Root 可勾选（级联整棵树）；用例行也参与勾选（勾功能点会带上它的用例），只有“未绑定”容器不参与。
            boolean unboundContainer = "unbound".equals(row.id());
            if (unboundContainer) check.setAttribute("disabled", true);
            line.getElement().appendChild(check);
            if (row.id() != null) {
                rowElements.put(row.id(), line.getElement());
                checkElements.put(row.id(), check);
                checkedState.put(row.id(), row.checked());
                halfState.put(row.id(), row.indeterminate());
                if (row.selected()) activeState.add(row.id());
            }

            // 勾选框紧贴标题，类型徽标放在标题后面，避免把勾选框和名字挤开。
            Span label = new Span(row.name() == null ? "" : row.name());
            label.addClassName("ft-label");
            line.add(label);
            if (row.badge() != null && !row.badge().isBlank()) {
                Span badge = new Span(row.badge());
                badge.addClassName("ft-badge");
                line.add(badge);
            }
            // 节点下方的用例条数：父节点的数 = 子节点之和 + 自己直接挂的用例，根与顶部「用例 N」一致。
            if (row.count() > 0) {
                Span count = new Span(Integer.toString(row.count()));
                count.addClassName("ft-count");
                count.getElement().setAttribute("title", "该节点下共 " + row.count() + " 条用例");
                line.add(count);
            }

            if (!row.synthetic()) {
                line.getElement().setAttribute("draggable", "true");
            }
            node.add(line);

            List<Row> kids = children.getOrDefault(row.id(), List.of());
            if (row.hasChildren() && row.expanded() && !kids.isEmpty()) {
                Div sub = new Div();
                sub.addClassName("ft-children");
                renderLevel(sub, kids, children);
                node.add(sub);
            }
            parent.add(node);
        }
    }

    private void installClientEvents() {
        getElement().executeJs("""
                const root = this;
                if (root.__ftReady) return;
                root.__ftReady = true;
                let dragId = null;

                const rowOf = (target) => {
                    const row = target && target.closest ? target.closest('.ft-row') : null;
                    return row && root.contains(row) ? row : null;
                };
                const clearDrop = () => root.querySelectorAll('.drop-target,.drop-before,.drop-after')
                        .forEach(el => el.classList.remove('drop-target', 'drop-before', 'drop-after'));
                // 行的上 30% = 插到它前面，下 30% = 插到它后面，中间 40% = 挂到它下面。
                const zoneOf = (row, y) => {
                    const rect = row.getBoundingClientRect();
                    const offset = y - rect.top;
                    if (offset < rect.height * 0.3) return 'before';
                    if (offset > rect.height * 0.7) return 'after';
                    return 'into';
                };

                root.addEventListener('click', (e) => {
                    const row = rowOf(e.target);
                    if (!row) return;
                    const id = row.dataset.id;
                    if (!id) return;
                    if (e.target.classList && e.target.classList.contains('ft-chevron')) {
                        e.preventDefault();
                        root.$server.toggleNode(id);
                        return;
                    }
                    if (e.target.tagName === 'INPUT') {
                        e.preventDefault();
                        // 勾选框走独立入口：级联勾选/取消子孙，并回推父级勾选状态。
                        root.$server.toggleCheck(id);
                        return;
                    }
                    root.$server.selectNode(id, e.ctrlKey || e.metaKey, e.shiftKey);
                });

                root.addEventListener('dblclick', (e) => {
                    const row = rowOf(e.target);
                    if (!row || !row.dataset.id || (e.target.tagName === 'INPUT')) return;
                    root.$server.openNode(row.dataset.id);
                });

                // 右键：交给服务端弹自绘菜单（节点/用例行各有不同动作）。
                root.addEventListener('contextmenu', (e) => {
                    const row = rowOf(e.target);
                    if (!row || !row.dataset.id) return;
                    e.preventDefault();
                    e.stopPropagation();
                    if (e.target.tagName === 'INPUT') e.target.blur();
                    root.$server.openMenu(row.dataset.id, e.clientX, e.clientY);
                });

                root.addEventListener('dragstart', (e) => {
                    const row = rowOf(e.target);
                    if (!row || !row.dataset.id || row.getAttribute('draggable') !== 'true') return;
                    dragId = row.dataset.id;
                    row.classList.add('dragging');
                    try { e.dataTransfer.setData('text/plain', dragId); e.dataTransfer.effectAllowed = 'move'; } catch (ignored) {}
                });

                root.addEventListener('dragend', () => {
                    dragId = null;
                    root.querySelectorAll('.dragging').forEach(el => el.classList.remove('dragging'));
                    clearDrop();
                });

                root.addEventListener('dragover', (e) => {
                    const row = rowOf(e.target);
                    if (!row || !dragId) return;
                    e.preventDefault();
                    const zone = 'ft-root' === row.dataset.id ? 'into' : zoneOf(row, e.clientY);
                    if (!row.classList.contains('drop-' + zone)) {
                        clearDrop();
                        row.classList.add(zone === 'into' ? 'drop-target' : 'drop-' + zone);
                    }
                });

                root.addEventListener('dragleave', (e) => {
                    const row = rowOf(e.target);
                    if (row) row.classList.remove('drop-target');
                });

                root.addEventListener('drop', (e) => {
                    const row = rowOf(e.target);
                    if (!row || !dragId) return;
                    e.preventDefault();
                    const targetId = row.dataset.id;
                    const zone = 'ft-root' === targetId ? 'into' : zoneOf(row, e.clientY);
                    clearDrop();
                    if (targetId && targetId !== dragId) root.$server.dropNode(dragId, targetId, zone);
                    dragId = null;
                });
                """);
    }

    @ClientCallable
    public void toggleNode(String id) {
        handler.toggle(id);
    }

    @ClientCallable
    public void selectNode(String id, boolean ctrl, boolean shift) {
        handler.select(id, ctrl, shift);
    }

    @ClientCallable
    public void toggleCheck(String id) {
        handler.check(id);
    }

    @ClientCallable
    public void openNode(String id) {
        handler.open(id);
    }

    @ClientCallable
    public void moveNode(String draggedId, String targetId) {
        handler.move(draggedId, targetId);
    }

    @ClientCallable
    public void dropNode(String draggedId, String targetId, String zone) {
        handler.reorder(draggedId, targetId, zone);
    }

    @ClientCallable
    public void openMenu(String id, double x, double y) {
        handler.menu(id, x, y);
    }

    @ClientCallable
    public void menuPick(String id, String action) {
        handler.menuPick(id, action);
    }

    /**
     * 在鼠标位置弹出自绘右键菜单。菜单挂在 body 上（不被树的滚动容器裁掉），
     * 点外部 / 滚轮 / Esc 关闭；点中某项后回调 {@link Handler#menuPick}。
     *
     * @param itemsJson [{action,label,disabled?,danger?,sep?}, ...]
     */
    public void showMenu(String id, double x, double y, String itemsJson) {
        getElement().executeJs("""
                const root = this;
                // $0 是 JSON 字符串（executeJs 不会帮我们解析），必须自己 parse，否则会被按字符遍历。
                const items = JSON.parse($0);
                const nodeId = $1;
                const x = $2;
                const y = $3;
                document.querySelectorAll('.ft-menu').forEach(m => m.remove());

                const menu = document.createElement('div');
                menu.className = 'ft-menu';
                for (const it of items) {
                    if (it.sep) {
                        const line = document.createElement('div');
                        line.className = 'ft-menu-sep';
                        menu.appendChild(line);
                        continue;
                    }
                    const item = document.createElement('div');
                    item.className = 'ft-menu-item'
                        + (it.danger ? ' danger' : '')
                        + (it.disabled ? ' disabled' : '');
                    item.textContent = it.label;
                    if (!it.disabled) {
                        item.addEventListener('click', (ev) => {
                            ev.stopPropagation();
                            closeMenu();
                            root.$server.menuPick(nodeId, it.action);
                        });
                    }
                    menu.appendChild(item);
                }
                document.body.appendChild(menu);

                const rect = menu.getBoundingClientRect();
                menu.style.left = Math.max(6, Math.min(x, window.innerWidth - rect.width - 8)) + 'px';
                menu.style.top = Math.max(6, Math.min(y, window.innerHeight - rect.height - 8)) + 'px';

                const onDown = (ev) => { if (!menu.contains(ev.target)) closeMenu(); };
                const onKey = (ev) => { if (ev.key === 'Escape') closeMenu(); };
                const onScroll = () => closeMenu();
                function closeMenu() {
                    menu.remove();
                    document.removeEventListener('mousedown', onDown, true);
                    document.removeEventListener('keydown', onKey, true);
                    window.removeEventListener('scroll', onScroll, true);
                    window.removeEventListener('resize', onScroll, true);
                }
                setTimeout(() => {
                    document.addEventListener('mousedown', onDown, true);
                    document.addEventListener('keydown', onKey, true);
                    window.addEventListener('scroll', onScroll, true);
                    window.addEventListener('resize', onScroll, true);
                }, 0);
                """, itemsJson, id, x, y);
    }
}

package com.yuceyang.uitest.ui;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 截图上的多选区框选器：每拖一次就多一个框，松手后把选区换算成图片原始像素坐标回调给服务端。
 * 坐标换算在浏览器里做（naturalWidth / clientWidth），不受弹框缩放影响。
 *
 * <p>一个页面上的目标经常分散在几处（搜索框在右上、操作按钮在列表右侧），只框一个区域识别不全，
 * 所以这里允许连续框多个：每个框左上角带序号（AI 提示词里按这个顺序描述），右上角有 × 单独删除，
 * 「清除选区」一次清空。点在已有框上不会开始新框，避免误覆盖。</p>
 *
 * <p>结构是「可滚动容器 → canvas（图片 + 覆盖层）」：覆盖层跟着 canvas 一起长到整张图的高度，
 * 所以截图被容器裁掉的下半部分也能框；拖到容器上下边缘时自动滚屏，一次按住就能框到图底。</p>
 */
public class ShotRegionPicker extends Div {
    private final Consumer<List<int[]>> onChanged;
    private final List<int[]> regions = new ArrayList<>();

    public ShotRegionPicker(String imageSrc, Consumer<List<int[]>> onChanged) {
        addClassName("shot-picker");
        Image image = new Image(imageSrc, "截图");
        image.addClassName("shot-picker-image");
        Div overlay = new Div();
        overlay.addClassName("shot-picker-overlay");
        overlay.getElement().setAttribute("title", "按住鼠标拖一个框，重复拖可以再加一个；拖到上下边缘会自动滚屏");
        Div canvas = new Div(image, overlay);
        canvas.addClassName("shot-picker-canvas");
        add(canvas);
        this.onChanged = onChanged;
        getElement().executeJs(JS);
    }

    /** 当前所有选区（图片原始像素），按框选顺序；没有选区时是空列表。 */
    public List<int[]> regions() {
        return List.copyOf(regions);
    }

    public void clearRegions() {
        regions.clear();
        getElement().executeJs("""
                this.querySelectorAll('.shot-picker-box').forEach(b => b.remove());
                this.__qtsBoxes = [];
                """);
        notifyChanged();
    }

    @ClientCallable
    public void selected(int x, int y, int width, int height) {
        if (width < 6 || height < 6) return;
        regions.add(new int[]{Math.max(0, x), Math.max(0, y), width, height});
        notifyChanged();
    }

    /** 浏览器里删掉了第 index 个框（0 起，与 {@link #selected} 的到达顺序一致）。 */
    @ClientCallable
    public void removed(int index) {
        if (index < 0 || index >= regions.size()) return;
        regions.remove(index);
        notifyChanged();
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.accept(regions());
    }

    static final String JS = """
            const root = this;
            if (root.__qtsPicker) return;
            root.__qtsPicker = true;
            const img = root.querySelector('img');
            const overlay = root.querySelector('.shot-picker-overlay');
            const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);
            let start = null;      // 起点：相对覆盖层的内容坐标（滚屏时不变）
            let box = null;        // 正在拖的那条橡皮筋（松手后换成正式框）
            let last = null;       // 最近一次指针位置（视口坐标）
            let raf = 0;
            const boxes = () => root.__qtsBoxes || (root.__qtsBoxes = []);
            const toContent = (clientX, clientY) => {
              const r = overlay.getBoundingClientRect();
              return {x: clamp(clientX - r.left, 0, r.width), y: clamp(clientY - r.top, 0, r.height)};
            };
            const draw = () => {
              if (!start || !box || !last) return;
              const p = toContent(last.x, last.y);
              box.style.left = Math.min(start.x, p.x) + 'px';
              box.style.top = Math.min(start.y, p.y) + 'px';
              box.style.width = Math.abs(p.x - start.x) + 'px';
              box.style.height = Math.abs(p.y - start.y) + 'px';
            };
            const renumber = () => boxes().forEach((entry, i) => {
              const badge = entry.el.querySelector('.shot-picker-badge');
              if (badge) badge.textContent = String(i + 1);
            });
            const addBox = (rect) => {
              const el = document.createElement('div');
              el.className = 'shot-picker-box';
              el.style.left = rect.left + 'px';
              el.style.top = rect.top + 'px';
              el.style.width = rect.width + 'px';
              el.style.height = rect.height + 'px';
              const badge = document.createElement('span');
              badge.className = 'shot-picker-badge';
              badge.textContent = String(boxes().length + 1);
              const del = document.createElement('button');
              del.type = 'button';
              del.className = 'shot-picker-remove';
              del.title = '删除这个选区';
              del.textContent = '×';
              el.appendChild(badge);
              el.appendChild(del);
              overlay.appendChild(el);
              const entry = {el: el};
              boxes().push(entry);
              del.addEventListener('click', (ev) => {
                ev.stopPropagation();
                const index = boxes().indexOf(entry);
                if (index < 0) return;
                el.remove();
                boxes().splice(index, 1);
                renumber();
                root.$server.removed(index);
              });
              return el;
            };
            // 拖到容器上下边缘 36px 内自动滚屏，边滚边把框画到滚出来的区域上。
            const EDGE = 36, STEP = 14;
            const autoScroll = () => {
              if (!start || !last) return;
              const r = root.getBoundingClientRect();
              if (last.y > r.bottom - EDGE) root.scrollTop += STEP;
              else if (last.y < r.top + EDGE) root.scrollTop -= STEP;
              draw();
              raf = requestAnimationFrame(autoScroll);
            };
            const stop = () => { cancelAnimationFrame(raf); raf = 0; };
            overlay.addEventListener('pointerdown', (e) => {
              // 点在已有框（或它的删除键）上时不开始新框，否则想删一个框会顺手多画一个
              if (e.target.closest && e.target.closest('.shot-picker-box')) return;
              if (box) { box.remove(); box = null; }
              start = toContent(e.clientX, e.clientY);
              last = {x: e.clientX, y: e.clientY};
              box = document.createElement('div');
              box.className = 'shot-picker-box';
              overlay.appendChild(box);
              overlay.setPointerCapture(e.pointerId);
              draw();
              stop();
              raf = requestAnimationFrame(autoScroll);
              e.preventDefault();
            });
            overlay.addEventListener('pointermove', (e) => {
              if (!start) return;
              last = {x: e.clientX, y: e.clientY};
              draw();
            });
            const finish = (e) => {
              if (!start || !box) return;
              stop();
              const p = toContent(e.clientX, e.clientY);
              const left = Math.min(start.x, p.x);
              const top = Math.min(start.y, p.y);
              const width = Math.abs(p.x - start.x);
              const height = Math.abs(p.y - start.y);
              start = null; last = null;
              box.remove(); box = null;
              if (width < 6 || height < 6) return;
              addBox({left: left, top: top, width: width, height: height});
              const scaleX = img.naturalWidth / (img.clientWidth || 1);
              const scaleY = img.naturalHeight / (img.clientHeight || 1);
              root.$server.selected(Math.round(left * scaleX), Math.round(top * scaleY),
                                    Math.round(width * scaleX), Math.round(height * scaleY));
            };
            overlay.addEventListener('pointerup', finish);
            overlay.addEventListener('pointercancel', () => {
              stop(); start = null; last = null;
              if (box) { box.remove(); box = null; }
            });
            """;
}

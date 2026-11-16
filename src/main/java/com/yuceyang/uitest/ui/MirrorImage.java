package com.yuceyang.uitest.ui;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.html.Image;

/**
 * 浏览器镜像。客户端事件必须落在声明 {@link ClientCallable} 的组件元素上，
 * 所以把转发方法放在 Image 子类里，而不是外层 WorkspaceView。
 */
public class MirrorImage extends Image {
    public interface Handler {
        void click(double x, double y, int button, int clickCount);
        void wheel(double deltaX, double deltaY);
        void key(String key, String text, boolean control, boolean alt, boolean shift);
        void marquee(double x1, double y1, double x2, double y2, boolean additive);
        void toggleBox(String elementId);
        void sync();
    }

    private final Handler handler;

    public MirrorImage(Handler handler) {
        this.handler = handler;
        addClassName("browser-image");
    }

    @ClientCallable
    public void mirrorClick(double x, double y, int button, int clickCount) {
        handler.click(x, y, button, clickCount);
    }

    @ClientCallable
    public void mirrorWheel(double deltaX, double deltaY) {
        handler.wheel(deltaX, deltaY);
    }

    @ClientCallable
    public void mirrorKey(String key, String text, boolean control, boolean alt, boolean shift) {
        handler.key(key, text, control, alt, shift);
    }

    @ClientCallable
    public void mirrorMarquee(double x1, double y1, double x2, double y2, boolean additive) {
        handler.marquee(x1, y1, x2, y2, additive);
    }

    @ClientCallable
    public void mirrorToggleBox(String elementId) {
        handler.toggleBox(elementId);
    }

    @ClientCallable
    public void mirrorSync() {
        handler.sync();
    }
}

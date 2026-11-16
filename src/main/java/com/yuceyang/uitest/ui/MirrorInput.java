package com.yuceyang.uitest.ui;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.html.Input;

/**
 * 镜像输入代理：一个隐藏的 input 承接键盘与输入法事件，
 * 这样中文组字、粘贴、复制都能走浏览器原生流程，再转发到真实页面。
 */
public class MirrorInput extends Input {
    public interface Handler {
        void insert(String text);
        void press(String key);
        String copy();
        void sync();
    }

    private final Handler handler;

    public MirrorInput(Handler handler) {
        this.handler = handler;
        setId("qts-ime");
        getElement().setAttribute("autocomplete", "off");
        getElement().setAttribute("spellcheck", "false");
        addClassName("mirror-ime");
    }

    @ClientCallable
    public void mirrorInsert(String text) {
        handler.insert(text);
    }

    @ClientCallable
    public void mirrorPress(String key) {
        handler.press(key);
    }

    @ClientCallable
    public String mirrorCopy() {
        return handler.copy();
    }

    @ClientCallable
    public void mirrorSync() {
        handler.sync();
    }
}

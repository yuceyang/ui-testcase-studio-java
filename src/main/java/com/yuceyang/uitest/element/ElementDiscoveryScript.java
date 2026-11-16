package com.yuceyang.uitest.element;

public final class ElementDiscoveryScript {
    private ElementDiscoveryScript() {}

    public static final String JAVASCRIPT = """
            () => {
              const nativeTags = new Set(['BUTTON','A','INPUT','SELECT','TEXTAREA','SUMMARY']);
              // 只有这些 role 才代表"可交互"，role=dialog/table/presentation 之类的外壳不能当元素。
              const interactiveRoles = new Set(['button','link','tab','menuitem','menuitemcheckbox',
                'menuitemradio','option','checkbox','radio','switch','textbox','searchbox',
                'combobox','listbox','slider','spinbutton']);
              const componentSelectors = [
                ['.n-pagination,.el-pagination,.ant-pagination','分页','select'],
                ['.n-date-picker,.el-date-editor,.ant-picker,[class*=date-picker],[class*=time-picker]','时间/日期选择','input'],
                ['.n-select,.el-select,.ant-select','下拉选择','select'],
                ['.n-checkbox,.el-checkbox,.ant-checkbox','复选选择','check'],
                ['.n-radio,.el-radio,.ant-radio','单选选择','check'],
                ['.n-upload,.el-upload,.ant-upload','文件上传','file']
              ];
              // 选择器必须唯一：两个结构完全相同的日期区间（左右两栏各一个）如果只取固定层数，
              // 会因为区分它们的祖先在更上层而得到同一个选择器，被当成同一个元素去重掉。
              // 所以这里逐级向上补路径，一旦当前路径在文档里唯一就停；到 html 还不唯一就把整条路径给出来。
              const cssPath = (el) => {
                if (el.id && !/^\\d/.test(el.id) && !/[0-9a-f]{8,}/i.test(el.id)) return '#' + CSS.escape(el.id);
                const testId = el.getAttribute('data-testid') || el.getAttribute('data-test');
                if (testId) return '[data-testid="' + CSS.escape(testId) + '"]';
                const parts = [];
                let node = el;
                while (node && node.nodeType === 1) {
                  let part = node.localName;
                  if (node.id && !/[0-9a-f]{8,}/i.test(node.id)) { parts.unshift(part + '#' + CSS.escape(node.id)); break; }
                  const stableClasses = [...node.classList].filter(c => !/^(_|[a-z]+_)?[a-z0-9]{5,}[_-][a-z0-9_]*\\d/i.test(c) && !/^(active|hover|focus|selected|open|disabled)$/i.test(c)).slice(0,2);
                  if (stableClasses.length) part += '.' + stableClasses.map(c => CSS.escape(c)).join('.');
                  const siblings = node.parentElement ? [...node.parentElement.children].filter(x => x.localName === node.localName) : [];
                  if (siblings.length > 1) part += ':nth-of-type(' + (siblings.indexOf(node)+1) + ')';
                  parts.unshift(part);
                  const candidate = parts.join(' > ');
                  // 至少带 4 级上下文再判唯一，避免只给出 "input" 这种一改版就失效的路径。
                  if (parts.length >= 4) {
                    try {
                      if (document.querySelectorAll(candidate).length === 1) return candidate;
                    } catch (e) { }
                  }
                  if (node.localName === 'html' || parts.length >= 16) break;
                  node = node.parentElement;
                }
                return parts.join(' > ');
              };
              const textOf = (el) => {
                const direct = (el.innerText || el.textContent || '').replace(/\\s+/g,' ').trim();
                return (el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('title') || direct || el.value || '').slice(0,120);
              };

              // —— 顶层弹窗：弹窗打开时整页不可交互，只识别弹窗里的元素 ——
              const layerSelector = [
                '[role=dialog]','[role=alertdialog]','[aria-modal=true]','dialog[open]',
                '.el-dialog','.el-drawer','.ant-modal','.ant-drawer','.n-modal','.n-drawer',
                '.modal.show','.modal.in','.layui-layer-page','.weui-desktop-dialog',
                'vaadin-dialog-overlay','vaadin-confirm-dialog-overlay','vaadin-message-overlay'
              ].join(',');
              const shownBox = (el) => {
                const rect = el.getBoundingClientRect();
                if (rect.width < 120 || rect.height < 60) return null;
                if (rect.bottom <= 0 || rect.right <= 0 || rect.top >= innerHeight || rect.left >= innerWidth) return null;
                const style = getComputedStyle(el);
                if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return null;
                return rect;
              };
              const zIndexOf = (el) => {
                let z = 0;
                for (let node = el; node && node.nodeType === 1; node = node.parentElement) {
                  const value = parseInt(getComputedStyle(node).zIndex, 10);
                  if (!isNaN(value)) z = Math.max(z, value);
                }
                return z;
              };
              const layers = [...document.querySelectorAll(layerSelector)]
                  .filter(el => el instanceof HTMLElement && shownBox(el));
              // 嵌套弹窗取最内层：把"里面还套着另一个弹窗"的父级去掉。
              const innerLayers = layers.filter(el => !layers.some(other => other !== el && el.contains(other)));
              let topLayer = null;
              for (const el of innerLayers) {
                if (!topLayer) { topLayer = el; continue; }
                const za = zIndexOf(el), zb = zIndexOf(topLayer);
                const later = (topLayer.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
                if (za > zb || (za === zb && later)) topLayer = el;
              }
              // 整个弹窗都是识别范围（标题、字段、底部按钮都要），不要只取其中一小块。
              const scope = topLayer || document.body;

              const seen = new Set();
              const refs = [];
              const results = [];
              const push = (el, name, kind, component) => {
                if (!name) return;
                const selector = cssPath(el);
                const key = kind + '|' + name + '|' + selector;
                if (seen.has(key)) return;
                seen.add(key);
                const rect = el.getBoundingClientRect();
                refs.push(el);
                results.push({ name, kind, component: component || '', selector,
                  semanticPath: selector, visible: true, occluded: false, area: rect.width * rect.height,
                  testId: el.getAttribute('data-testid') || '', stableId: el.id || '', ariaLabel: el.getAttribute('aria-label') || '',
                  x: rect.left, y: rect.top, w: rect.width, h: rect.height });
              };

              // —— 图表 / 地图 / 监控大屏：画布本身点不动，按图例或整体标题统一识别 ——
              // 有图例（echarts、highcharts、自绘图例）就逐个图例项当识别元素；
              // 没有图例的地图/实时监控页，用所在面板的标题作为一个整体识别元素。
              const chartHosts = [];
              const chartishClass = (node) => {
                const cls = typeof node.className === 'string' ? node.className : '';
                return /(echarts|chart|map|monitor|dashboard|gauge|topology|screen)/i.test(cls);
              };
              const chartHostOf = (el) => {
                let node = el;
                for (let i = 0; i < 4 && node && node.nodeType === 1; i++, node = node.parentElement) {
                  if (chartishClass(node) || node.localName === 'canvas') return node;
                }
                return null;
              };
              const legendItems = (host) => {
                const boxes = [...host.querySelectorAll('[class*=legend],[class*=Legend]')];
                const items = [];
                for (const box of boxes) {
                  items.push(...[...box.querySelectorAll('li,[class*=item],span')].filter(x => {
                    const t = (x.innerText || '').trim();
                    return t && t.length <= 24 && x.getBoundingClientRect().width > 2;
                  }));
                }
                return items.filter(x => !items.some(other => other !== x && x.contains(other)));
              };
              const titleOf = (host) => {
                const read = (probe) => {
                  if (!probe) return '';
                  const text = (probe.innerText || '').replace(/\\s+/g, ' ').trim();
                  return text.length > 60 ? text.slice(0, 60) : text;
                };
                // 先看容器自己带的标题（监控大屏、地图卡片通常把标题放在里面）。
                const own = read(host.querySelector('h1,h2,h3,h4,h5,[class*=title]'));
                if (own) return own;
                // 再看外层面板的标题，避免误抓页面别处的第一个标题。
                let outer = host.parentElement;
                for (let i = 0; i < 2 && outer; i++, outer = outer.parentElement) {
                  const probe = [...outer.querySelectorAll('h1,h2,h3,h4,h5,[class*=title]')]
                      .find(node => !node.contains(host) && !host.contains(node) && !node.closest('[class*=echarts]'));
                  const text = read(probe);
                  if (text) return text;
                }
                return '';
              };
              for (const el of scope.querySelectorAll('canvas,[class*=echarts],[class*=chart],[class*=map],[class*=monitor],[class*=dashboard],[class*=topology]')) {
                if (!(el instanceof HTMLElement) || el.closest('svg,script,style')) continue;
                const rect = el.getBoundingClientRect();
                if (rect.width < 80 || rect.height < 60) continue;
                if (el.localName !== 'canvas' && !el.querySelector('canvas,svg')) continue;
                const host = chartHostOf(el) || el;
                if (chartHosts.some(existing => existing === host || existing.contains(host) || host.contains(existing))) continue;
                chartHosts.push(host);
                const legends = legendItems(host);
                if (legends.length) {
                  for (const item of legends) push(item, textOf(item), 'chart', '图表图例');
                  continue;
                }
                const title = titleOf(host) || textOf(host);
                if (title) push(host, title, 'chart', '图表/地图整体');
              }

              for (const el of scope.querySelectorAll('*')) {
                if (!(el instanceof HTMLElement) || el.closest('script,style,svg')) continue;
                if (chartHosts.some(host => host === el || host.contains(el))) continue;
                const rect = el.getBoundingClientRect();
                const style = getComputedStyle(el);
                if (rect.width < 3 || rect.height < 3 || style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) continue;
                if (rect.bottom < 0 || rect.right < 0 || rect.top > innerHeight * 2 || rect.left > innerWidth * 2) continue;
                let occluded = false;
                const cx = Math.min(innerWidth - 1, Math.max(0, rect.left + rect.width / 2));
                const cy = Math.min(innerHeight - 1, Math.max(0, rect.top + rect.height / 2));
                if (rect.top >= 0 && rect.left >= 0 && rect.top < innerHeight && rect.left < innerWidth) {
                  const top = document.elementFromPoint(cx, cy);
                  occluded = !!top && top !== el && !el.contains(top) && !top.contains(el);
                }
                if (occluded) continue;
                const cls = typeof el.className === 'string' ? el.className : '';
                const role = (el.getAttribute('role') || '').toLowerCase();
                const isNative = nativeTags.has(el.localName.toUpperCase()) || interactiveRoles.has(role)
                    || el.hasAttribute('onclick') || el.hasAttribute('tabindex');
                const isCustom = [...el.attributes].some(a => /^@click|^v-on|^ng-click/i.test(a.name)) || /(^|[\\s_-])(btn|button)(?=[\\s_-]|$)/i.test(cls);
                const component = componentSelectors.find(([selector]) => el.matches(selector));
                if (!isNative && !isCustom && !component) continue;
                if (el.parentElement && el.parentElement.closest('button,a,[role=button]') && !component) continue;
                const name = textOf(el);
                if (!name) continue;
                let kind = 'other';
                if (component) kind = component[2];
                else if (el.matches('input[type=checkbox],input[type=radio]')) kind = 'check';
                else if (el.matches('input[type=file]')) kind = 'file';
                else if (el.matches('input,textarea')) kind = 'input';
                else if (el.matches('select')) kind = 'select';
                else if (el.matches('a')) kind = 'link';
                else if (el.matches('button,[role=button]') || isCustom) kind = 'button';
                push(el, name, kind, component ? component[1] : '');
                if (results.length >= 800) break;
              }

              // 最小元素原则：外壳容器（弹窗体、表单组、列表行包装）不算可交互元素——
              // 只要它内部还有被识别到的元素，就把它自己丢掉。
              return results.filter((item, index) => {
                if (item.kind !== 'other' || item.component) return true;
                return !results.some((other, j) => j !== index && refs[index].contains(refs[j]));
              });
            }
            """;
}

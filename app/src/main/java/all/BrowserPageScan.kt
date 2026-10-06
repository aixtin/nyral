package io.github.aixtin.nyral

/**
 * BrowserPage 扫描注入域(第二刀拆分, BP8):
 * - injectScanner: 页面加载后注入元素扫描器, 收集可见可交互元素(视口坐标, 上报即当前屏幕所见)
 * - 同一视觉块(矩形高度重叠+同文本)只保留面积最小的一份, 消除容器/叶子重复采集
 * - 标签清洗: 剥离 "[object ...]" 字符串化伪影(站点把 JS 对象插值进 aria-label/正文), 剥空的候选直接弃采, 杜绝按垃圾标签匹配失败
 * - 扫描不复位页面滚动, 保证"滚动->重扫"工作流不被打断
 * - 主类仅保留调用点(initWebViews 的 onPageFinished -> injectScanner())
 */
internal fun BrowserPage.injectScanner() {
    web.evaluateJavascript(
        """(function(){
          var MAX=150;
          var cands=[];
          var seen=new Set();
          function cleanTxt(t){ t=(t||'').replace(/\[object[^\]]*\]/g,'').trim(); return t.length>40?t.slice(0,40):t; }
          function norm(t){ return (t||'').replace(/\s+/g,' ').trim(); }
          function collect(el,ox,oy){
            if(cands.length>=MAX) return;
            if(seen.has(el)) return; seen.add(el);
            try{
              var r=el.getBoundingClientRect();
              var tag=el.tagName;
              // 交互元素放宽到 16px: 桌面式后台的文字链接/图标项常只有 16~20px 高(Halo 文章标题链接仅 18px), 24px 阈值会把整行列表漏采
              var actEl=tag==='A'||tag==='BUTTON'||tag==='INPUT'||tag==='TEXTAREA'||tag==='SELECT'||el.isContentEditable||/^(button|link|tab|menuitem)$/.test(el.getAttribute('role')||'');
              var minS=actEl?16:24;
              if(r.width<minS||r.height<minS) return;
              var st=el.ownerDocument.defaultView.getComputedStyle(el);
              if(st.visibility==='hidden'||st.display==='none'||st.opacity==='0') return;
              var txt='';
              if(tag==='INPUT'||tag==='TEXTAREA'){
                txt=((el.placeholder||'')+(el.name?('('+el.name+')'):'')+(el.value?('当前:'+el.value):''));
                if(!txt) txt=tag;
              } else {
                txt=(el.innerText||el.value||el.getAttribute('aria-label')||el.title||el.alt||'').trim();
                if(!txt&&tag==='IMG') txt='[图片]';
                if(!txt&&(tag==='DIV'||tag==='SPAN'||tag==='LI')) {
                  var c=el.querySelector('a,button,img,[role=button]');
                  if(c) txt=(c.innerText||c.getAttribute('aria-label')||c.title||c.alt||'').trim();
                }
              }
              // contenteditable 富文本(TipTap/ProseMirror 等)空态没有 innerText, 用占位提示兜底,
              // 否则正文框在空白时永远扫不到(扫不到→无法输入→恒空 的死局, Halo 发文失败根因之一)
              if(!txt && (el.isContentEditable||el.hasAttribute('contenteditable'))){
                var ph=el.getAttribute('data-placeholder')||el.getAttribute('aria-placeholder')||el.getAttribute('placeholder')||'';
                if(!ph){var phn=el.querySelector('[data-placeholder]'); if(phn) ph=phn.getAttribute('data-placeholder')||'';}
                txt=ph?('正文编辑区·'+ph):'正文编辑区';
              }
              // 无文字图标型控件兜底: 纯 svg 图标按钮/下拉触发器(更多⋯)自身无 innerText, 窄屏桌面式后台常把行操作
              // (编辑/删除)收进这种溢出菜单; 不兜底则 AI 既扫不到也点不开(Halo 文章列表删除失败根因)
              if(!txt){
                var named=el.getAttribute('aria-label')||el.getAttribute('title')||'';
                if(named){txt=named;}
                else{
                  var cn=(' '+((typeof el.className==='string')?el.className:((el.className&&el.className.baseVal)||''))+' ').toLowerCase();
                  var iconBtn=(tag==='BUTTON'||tag==='A'||el.getAttribute('role')==='button')&&!!el.querySelector('svg,[class*=icon],i');
                  var moreTrigger=/dropdown|menu|more|overflow|popover/.test(cn)&&(st.cursor==='pointer'||tag==='BUTTON'||tag==='A'||!!el.querySelector('svg'));
                  if(iconBtn||moreTrigger){
                    var rl='';var pp=el;
                    for(var z=0;z<6&&pp;z++){var aa=pp.querySelector&&pp.querySelector('a[href]');if(aa&&(aa.innerText||'').trim()){rl=aa.innerText.trim().slice(0,16);break;}pp=pp.parentElement;}
                    txt='更多操作'+(rl?('·'+rl):'');
                  }
                }
              }
              txt=cleanTxt(txt);
              if(!txt) return;
              // 视口坐标: 不加文档滚动偏移, 滚动后重扫即为新视口所见
              cands.push({el:el,x:Math.round(r.left+ox),y:Math.round(r.top+oy),
                          w:Math.round(r.width),h:Math.round(r.height),txt:txt});
            }catch(e){}
          }
          function collectFrame(fr,ox,oy){
            if(cands.length>=MAX) return;
            if(seen.has(fr)) return; seen.add(fr);
            try{
              var r=fr.getBoundingClientRect();
              if(r.width<24||r.height<24) return;
              var src=fr.getAttribute('src')||'';
              var host='';
              try{ host=src.match(/^https?:\/\/([^\/?#]+)/)[1]||''; }catch(e){}
              var txt=host?(host+' 内嵌页面'):'内嵌页面';
              if(src) txt=txt+' ['+src.slice(0,70)+']';
              cands.push({el:fr,x:Math.round(r.left+ox),y:Math.round(r.top+oy),
                          w:Math.round(r.width),h:Math.round(r.height),txt:txt});
            }catch(e){}
          }
          function scanDoc(doc,ox,oy){
            if(!doc||cands.length>=MAX) return;
            var sel='a,button,input,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[contenteditable],[tabindex],[onclick],[data-action],[data-testid],[data-clickable],li';
            try{ doc.querySelectorAll(sel).forEach(function(el){collect(el,ox,oy);}); }catch(e){}
            if(cands.length<MAX){
              try{
                doc.querySelectorAll('div,span').forEach(function(el){
                  if(cands.length>=MAX) return;
                  if(el.children.length>3) return;
                  var st=el.ownerDocument.defaultView.getComputedStyle(el);
                  var clickable = st.cursor==='pointer' || el.onclick!=null || el.getAttribute('onclick')!=null || el.getAttribute('data-action')!=null;
                  if(!clickable) return;
                  collect(el,ox,oy);
                });
              }catch(e){}
            }
            // iframe 穿透(内层坐标=iframe 在外层视口的位置 + 内层视口内偏移)
            try{
              doc.querySelectorAll('iframe').forEach(function(fr){
                if(cands.length>=MAX) return;
                var frr=fr.getBoundingClientRect();
                var fxo=ox+frr.left, fyo=oy+frr.top;
                var inner=null;
                try{ inner=fr.contentDocument; }catch(e){ inner=null; }
                if(inner&&inner!==doc){
                  collectFrame(fr,fxo,fyo);
                  scanDoc(inner,fxo,fyo);
                } else {
                  collectFrame(fr,fxo,fyo);
                }
              });
            }catch(e){}
            // shadow DOM 穿透
            try{
              doc.querySelectorAll('*').forEach(function(el){
                if(cands.length>=MAX) return;
                if(el.shadowRoot){
                  var sr=el.getBoundingClientRect();
                  scanDoc(el.shadowRoot,ox+sr.left,oy+sr.top);
                }
              });
            }catch(e){}
          }
          // 视觉去重: 归一文本相同且重叠区占较小者面积>=0.8 视为同一视觉块,
          // 只留面积最小(定位最精确)的一份; 面积相同保留先采集的一份
          function dedup(){
            var keep=[];
            for(var i=0;i<cands.length;i++){
              var a=cands[i], drop=false;
              for(var j=0;j<cands.length&&!drop;j++){
                if(i===j) continue;
                var b=cands[j];
                if(norm(a.txt)!==norm(b.txt)) continue;
                var x1=Math.max(a.x,b.x), y1=Math.max(a.y,b.y);
                var x2=Math.min(a.x+a.w,b.x+b.w), y2=Math.min(a.y+a.h,b.y+b.h);
                if(x2<=x1||y2<=y1) continue;
                var ov=(x2-x1)*(y2-y1);
                var smaller=Math.min(a.w*a.h,b.w*b.h);
                if(ov/smaller>=0.8){
                  if(b.w*b.h<a.w*a.h) drop=true;
                  else if(b.w*b.h===a.w*a.h&&j<i) drop=true;
                }
              }
              if(!drop) keep.push(a);
            }
            return keep;
          }
          // 清除上次扫描残留的 data-scan 标记, 防止新旧索引错位
          function clearMarks(doc){
            try{
              doc.querySelectorAll('[data-scan]').forEach(function(el){el.removeAttribute('data-scan');});
              doc.querySelectorAll('iframe').forEach(function(fr){
                try{ if(fr.contentDocument) clearMarks(fr.contentDocument); }catch(e){}
              });
              doc.querySelectorAll('*').forEach(function(el){
                if(el.shadowRoot) clearMarks(el.shadowRoot);
              });
            }catch(e){}
          }
          // 可交互判定: 容器剔除时认定"叶子"用
          function interactive(el){
            var t=el.tagName;
            return t==='A'||t==='BUTTON'||t==='INPUT'||t==='SELECT'||t==='TEXTAREA'||t==='OPTION'||
                   el.isContentEditable||
                   el.hasAttribute('onclick')||/^(button|link|tab|menuitem)$/.test(el.getAttribute('role')||'');
          }
          // 容器剔除: 祖先块内含可交互叶子(叶子基本盖住祖先+叶子文本是祖先文本子串)时只留叶子,
          // 消除"容器+叶子同列"造成的视觉序错乱与点击歧义
          function dropContainers(list){
            return list.filter(function(a){
              for(var j=0;j<list.length;j++){
                var b=list[j];
                if(b===a||!a.el.contains(b.el)) continue;
                if(!interactive(b.el)) continue;
                var x1=Math.max(a.x,b.x), y1=Math.max(a.y,b.y);
                var x2=Math.min(a.x+a.w,b.x+b.w), y2=Math.min(a.y+a.h,b.y+b.h);
                if(x2<=x1||y2<=y1) continue;
                if((x2-x1)*(y2-y1)/(a.w*a.h)<0.6) continue;
                var at=norm(a.txt), bt=norm(b.txt);
                if(bt&&at.indexOf(bt)>=0) return false;
              }
              return true;
            });
          }
          // 布局就绪重试: SPA(onPageFinished 后仍异步渲染)首扫常为空, 空则每 300ms 重扫最多 6 次;
          // 首次采集前等布局稳定(双 rAF + 高度连续不变), 防止加载/弹窗动画中被采到跨帧漂移坐标;
          // 结果按 y 再 x 排序后编号, 索引顺序即视觉顺序, 消除 DOM 序与视觉序倒挂
          var tries=0;
          function collectAndReport(){
            clearMarks(document);
            var kept=dropContainers(dedup());
            kept.sort(function(p,q){return p.y-q.y||p.x-q.x;});
            var out=[];
            for(var k=0;k<kept.length;k++){
              kept[k].el.setAttribute('data-scan',String(k));
              out.push({x:kept[k].x,y:kept[k].y,w:kept[k].w,h:kept[k].h,t:kept[k].txt});
            }
            daBridge.onElements(JSON.stringify(out));
          }
          function doScan(){
            cands=[]; seen=new Set();
            scanDoc(document,0,0);
            if(cands.length===0 && tries<6){ tries++; setTimeout(doScan, 300); return; }
            collectAndReport();
          }
          function waitStable(){
            var h0=-1, same=0, t0=Date.now();
            function tick(){
              var h=0; try{ h=document.body?document.body.clientHeight:0; }catch(e){}
              if(h>0&&h===h0){ same++; } else { same=0; }
              h0=h;
              var el=Date.now()-t0;
              if((same>=1&&el>=240)||el>800){ doScan(); return; }
              setTimeout(tick,120);
            }
            requestAnimationFrame(function(){requestAnimationFrame(tick);});
          }
          setTimeout(waitStable, 200);
        })();""", null)
}

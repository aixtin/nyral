package io.github.aixtin.nyral

/**
 * BrowserPage 扫描注入域(第二刀拆分, BP8):
 * - injectScanner: 页面加载后注入元素扫描器, 收集可见可交互元素(doc 绝对坐标)
 * - 主类仅保留调用点(initWebViews 的 onPageFinished -> injectScanner())
 */
internal fun BrowserPage.injectScanner() {
    web.evaluateJavascript(
        """(function(){
          var out=[];
          var MAX=150;
          function cleanTxt(t){ t=(t||'').trim(); return t.length>40?t.slice(0,40):t; }
          function push(el,ox,oy){
            if(out.length>=MAX) return;
            try{
              var r=el.getBoundingClientRect();
              if(r.width<24||r.height<24) return;
              var st=el.ownerDocument.defaultView.getComputedStyle(el);
              if(st.visibility==='hidden'||st.display==='none'||st.opacity==='0') return;
              var tag=el.tagName;
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
              txt=cleanTxt(txt);
              if(!txt) return;
              el.setAttribute('data-scan',String(out.length));
              out.push({x:Math.round(r.left+ox),y:Math.round(r.top+oy),
                        w:Math.round(r.width),h:Math.round(r.height),t:txt});
            }catch(e){}
          }
          function pushFrame(fr,ox,oy){
            if(out.length>=MAX) return;
            try{
              var r=fr.getBoundingClientRect();
              if(r.width<24||r.height<24) return;
              var src=fr.getAttribute('src')||'';
              var host='';
              try{ host=src.match(/^https?:\/\/([^\/?#]+)/)[1]||''; }catch(e){}
              var txt=host?(host+' 内嵌页面'):'内嵌页面';
              if(src) txt=txt+' ['+src.slice(0,70)+']';
              fr.setAttribute('data-scan',String(out.length));
              out.push({x:Math.round(r.left+ox),y:Math.round(r.top+oy),
                        w:Math.round(r.width),h:Math.round(r.height),t:txt});
            }catch(e){}
          }
          function scanDoc(doc,win,ox,oy){
            if(!doc||out.length>=MAX) return;
            var sel='a,button,input,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[contenteditable],[tabindex],[onclick],[data-action],[data-testid],[data-clickable],li';
            try{ doc.querySelectorAll(sel).forEach(function(el){push(el,ox,oy);}); }catch(e){}
            if(out.length<MAX){
              try{
                doc.querySelectorAll('div,span').forEach(function(el){
                  if(out.length>=MAX) return;
                  if(el.children.length>3) return;
                  var st=el.ownerDocument.defaultView.getComputedStyle(el);
                  if(st.cursor!=='pointer') return;
                  push(el,ox,oy);
                });
              }catch(e){}
            }
            // iframe 穿透
            try{
              doc.querySelectorAll('iframe').forEach(function(fr){
                if(out.length>=MAX) return;
                var frr=fr.getBoundingClientRect();
                var fxo=ox+frr.left+(win?win.scrollX:0);
                var fyo=oy+frr.top+(win?win.scrollY:0);
                var inner=null;
                try{ inner=fr.contentDocument; }catch(e){ inner=null; }
                if(inner&&inner!==doc){
                  pushFrame(fr,fxo,fyo);
                  scanDoc(inner,fr.contentWindow,fxo,fyo);
                } else {
                  pushFrame(fr,fxo,fyo);
                }
              });
            }catch(e){}
            // shadow DOM 穿透
            try{
              doc.querySelectorAll('*').forEach(function(el){
                if(out.length>=MAX) return;
                if(el.shadowRoot){
                  var sr=el.getBoundingClientRect();
                  scanDoc(el.shadowRoot,win,ox+sr.left+(win?win.scrollX:0),oy+sr.top+(win?win.scrollY:0));
                }
              });
            }catch(e){}
          }
          function doScan(){
            out=[];
            scanDoc(document,window,0,0);
            window.scrollTo(0,0);
            daBridge.onElements(JSON.stringify(out));
          }
          var steps=0;
          function warm(){
            steps++;
            window.scrollTo(0, document.body.scrollHeight);
            if(steps<3){ setTimeout(warm, 200); }
            else { window.scrollTo(0,0); setTimeout(doScan, 200); }
          }
          setTimeout(warm, 150);
        })();""", null)
}

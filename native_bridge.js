(function(){
  if(window.__ezNative||!window.EzAndroid)return;
  window.__ezNative=1;

  // ۱) دانلود فایل‌های blob (عکس، ویدیو، متن…) در پوشه‌ی Downloads
  var origClick=HTMLAnchorElement.prototype.click;
  async function send(blob,name){
    var id=Date.now()+'_'+Math.random().toString(36).slice(2);
    var size=3*349525; // مضرب ۳ تا base64 تکه‌ها به‌هم بچسبند
    for(var o=0;o<blob.size||o===0;o+=size){
      var part=blob.slice(o,o+size);
      var b64=await new Promise(function(res){
        var fr=new FileReader();
        fr.onload=function(){res(String(fr.result).split(',')[1]||'')};
        fr.readAsDataURL(part);
      });
      window.EzAndroid.chunk(id,b64,name,blob.type||'application/octet-stream',o+size>=blob.size);
    }
  }
  HTMLAnchorElement.prototype.click=function(){
    if(this.hasAttribute('download')&&/^blob:/i.test(this.href)){
      var name=this.getAttribute('download')||'file',url=this.href;
      fetch(url).then(function(r){return r.blob()}).then(function(b){return send(b,name)}).catch(function(){});
      return;
    }
    return origClick.apply(this,arguments);
  };

  // ۲) لینک‌های بیرونی در مرورگر گوشی باز شوند
  document.addEventListener('click',function(e){
    if(e.defaultPrevented||e.button>0)return;
    var t=e.target;if(!t||!t.closest)return;
    if(t.closest('button,.cb,input,select,textarea,[data-act]'))return;
    var a=t.closest('a[href]');if(!a||a.hasAttribute('download'))return;
    var h=a.href;if(!/^https?:\/\//i.test(h))return;
    e.preventDefault();
    window.EzAndroid.openUrl(h);
  },true);

  // ۳) رنگ نوار وضعیت و نوار پایین مطابق تم برنامه
  function push(){
    var m=document.querySelector('meta[name=theme-color]');
    if(m&&m.content)window.EzAndroid.themeColor(m.content);
  }
  push();
  new MutationObserver(push).observe(document.head,{subtree:true,childList:true,attributes:true,attributeFilter:['content']});
})();

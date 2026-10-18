import { runInNewContext } from "node:vm";
import { describe, expect, it } from "vitest";
import { ADMIN_PAGE } from "../src/admin-page";

// Minimal DOM surface used by the real editor: HTML replacement, scoped selectors,
// values, event handlers and connection state. No editor functions are mocked.
const decode = (s: string) => s.replace(/&(?:amp|lt|gt|quot|#39);/g, x => ({"&amp;":"&","&lt;":"<","&gt;":">","&quot;":'"',"&#39;":"'"}[x]!));
class Element {
  children: Element[] = [];
  parent: Element | null = null;
  value = "";
  textContent = "";
  disabled = false;
  checked = false;
  onclick: (() => unknown) | null = null;
  private html = "";
  constructor(public tag: string, public attrs: Record<string,string> = {}, private root = false) {
    this.value = attrs.value || ""; this.disabled = "disabled" in attrs; this.checked = "checked" in attrs;
  }
  get dataset() { return Object.fromEntries(Object.entries(this.attrs).filter(([k])=>k.startsWith("data-")).map(([k,v])=>[k.slice(5),v])); }
  get isConnected(): boolean { return this.root || !!this.parent?.isConnected; }
  get innerHTML() { return this.html; }
  set innerHTML(html: string) {
    for(const child of this.children) child.parent=null;
    this.children=[];this.html=html;
    const stack:Element[]=[this];
    for(const token of html.match(/<[^>]+>|[^<]+/g)||[]) {
      if(token.startsWith("</")) { stack.pop(); continue; }
      if(token.startsWith("<")) {
        const name=token.match(/^<([\w-]+)/)?.[1];if(!name)continue;
        const attrs:Record<string,string>={};
        for(const m of token.slice(name.length+1,-1).matchAll(/([\w-]+)(?:="([^"]*)")?/g)) attrs[m[1]]=decode(m[2]||"");
        const el=new Element(name,attrs);el.parent=stack.at(-1)!;el.parent.children.push(el);
        if(!["input","br","hr","meta","link"].includes(name))stack.push(el);
      } else { const el=stack.at(-1)!;el.textContent+=decode(token);if(el.tag==="textarea")el.value+=decode(token); }
    }
  }
  querySelectorAll(selector:string): Element[] {
    const parts=selector.split(/\s+/);
    const matches=(el:Element,part:string)=>part.startsWith("#")?el.attrs.id===part.slice(1):part.startsWith("[")?(()=>{const m=part.match(/^\[([^=\]]+)(?:="([^"]*)")?\]$/);return !!m&&m[1] in el.attrs&&(m[2]===undefined||el.attrs[m[1]]===m[2]);})():el.tag===part;
    const found:Element[]=[];
    const visit=(el:Element)=>{for(const child of el.children){if(matches(child,parts.at(-1)!)){let p=child.parent,i=parts.length-2;while(p&&i>=0){if(matches(p,parts[i]))i--;p=p.parent;}if(i<0)found.push(child);}visit(child);}};
    visit(this);return found;
  }
  querySelector(selector:string) { return this.querySelectorAll(selector)[0]||null; }
}
const settle = async () => {for(let i=0;i<30;i++)await Promise.resolve();};
const profile = (id="ab12",name="Home") => ({id,name,data:{sources:[],extraGuides:[]}});
function editor(write?: (path:string, opts:RequestInit)=>Promise<unknown>, reported: Record<string,{names:string[]}> = {}, stored = new Map<string,string>()) {
  const document=new Element("document",{},true);document.innerHTML='<main id="app"></main>';
  const calls:{path:string;opts:RequestInit}[]=[];
  const states=new Map<string,{revision:number;configured:boolean;maskedPreview:string|null}>();
  const fetch=async(path:string,opts:RequestInit={})=>{
    calls.push({path,opts});let result:unknown;
    if(path.endsWith("/api-key")) {
      const id=path.split("/")[4];let state=states.get(id)||{revision:1,configured:true,maskedPreview:id==="ab12"?"HOME********-123":"OTHR********-456"};
      if(opts.method) {if(write)result=await write(path,opts);else {const b=JSON.parse(opts.body as string);state={revision:b.revision+1,configured:opts.method==="PUT",maskedPreview:opts.method==="PUT"?b.apiKey.slice(0,4)+"********"+b.apiKey.slice(-4):null};states.set(id,state);result={profileId:id,...state};}}
      else result={profileId:id,...state};
    }
    else if(path.endsWith("/categories"))result={reported};
    else if(path==="/api/admin/lines")result={configured:false,lines:[]};
    else if(path==="/api/admin/profiles")result={profiles:[profile(),profile("cd34","Other")]};
    else result={ok:true};
    return new Response(JSON.stringify(result),{headers:{"content-type":"application/json"}});
  };
  const script=ADMIN_PAGE.split('<script>')[1].split('</script>')[0];
  const api=runInNewContext(script.slice(0,script.lastIndexOf('\ndocument.querySelectorAll("nav button")'))+'\n({editProfile})', {document,fetch,Response,localStorage:{getItem:(k:string)=>k==="ftv_admin"?"admin":stored.get(k)??null,setItem:(k:string,v:string)=>stored.set(k,v)},confirm:()=>true});
  const get=(selector:string)=>{const el=document.querySelector(selector);if(!el)throw new Error('Missing element '+selector);return el;};
  return {document,calls,get,open:(p: unknown=profile())=>api.editProfile(p)};
}

describe("real profile editor credential integration",()=>{
  it("shows only the confirmed saved preview, clears on removal, and isolates actual profile switches",async()=>{
    const e=editor();e.open();await settle();expect(e.get('[data-preview]').textContent).toBe('HOME********-123');expect(e.get('#rdkey').value).toBe('');
    const key='ABCDfixture-private-middleWXYZ';e.get('#rdkey').value=key;const pending=e.get('[data-save]').onclick!();expect(e.get('[data-preview]').textContent).toBe('');await pending;
    expect(e.get('[data-preview]').textContent).toBe('ABCD********WXYZ');expect(e.get('#rdkey').value).toBe('');
    expect(e.get('#app').innerHTML).not.toContain(key);expect(e.get('[data-preview]').textContent).not.toContain('fixture-private-middle');
    await e.get('[data-refresh]').onclick!();expect(e.get('[data-preview]').textContent).toBe('ABCD********WXYZ');
    e.open(profile('cd34','Other'));await settle();expect(e.get('[data-preview]').textContent).toBe('OTHR********-456');expect(e.get('#rdkey').value).toBe('');
    await e.get('[data-remove]').onclick!();expect(e.get('[data-preview]').textContent).toBe('');await e.get('[data-refresh]').onclick!();expect(e.get('[data-preview]').textContent).toBe('');
  });
  it("preserves unsaved profile fields through credential save, removal and status refresh",async()=>{
    const e=editor();e.open();await settle();
    e.get('#pname').value='Unsaved household';e.get('#ppin').value='4321';e.get('#xg').value='https://fixture.test/guide';e.get('#gp').value='News = fixture.news';
    const nameInput=e.get('#pname');
    e.get('#rdkey').value='fixture-only-editor-key-123';await e.get('[data-save]').onclick!();
    expect(e.get('#rdkey').value).toBe('');await e.get('[data-remove]').onclick!();await e.get('[data-refresh]').onclick!();
    expect(e.get('#pname')).toBe(nameInput);expect(e.get('#pname').value).toBe('Unsaved household');expect(e.get('#ppin').value).toBe('4321');
    expect(e.get('#xg').value).toBe('https://fixture.test/guide');expect(e.get('#gp').value).toBe('News = fixture.news');
    // Trigger the actual collect/paint path, then save the real draft.
    e.get('[data-kind]').onclick!();await settle();
    expect(e.get('#pname').value).toBe('Unsaved household');await e.get('#save').onclick!();await settle();
    const sent=JSON.parse(e.calls.find(c=>c.path==='/api/admin/profiles/ab12'&&c.opts.method==='PUT')!.opts.body as string);
    expect(sent).toMatchObject({name:'Unsaved household',data:{parentalPin:'4321',extraGuides:['https://fixture.test/guide'],guidePins:{News:'fixture.news'}}});
  });
  it("clears the visible key on actual rerender/navigation and excludes entered key from ordinary profile saves",async()=>{
    const e=editor();e.open();await settle();
    e.get('#rdkey').value='fixture-only-rerender-key-123';const old=e.get('#rdkey');e.get('[data-kind]').onclick!();await settle();
    expect(old.isConnected).toBe(false);expect(e.get('#rdkey').value).toBe('');
    e.get('#rdkey').value='fixture-only-never-profile-key-456';await e.get('#save').onclick!();await settle();
    const saved=e.calls.find(c=>c.path==='/api/admin/profiles/ab12'&&c.opts.method==='PUT')!;
    expect(saved.opts.body).not.toContain('fixture-only');expect(JSON.parse(saved.opts.body as string)).toEqual({name:'Home',data:{sources:[],extraGuides:[],guidePins:{}}});
    expect(e.document.querySelector('#rdkey')).toBeNull();
    e.open();await settle();e.get('#rdkey').value='fixture-only-navigation-key-789';await e.get('#back').onclick!();await settle();
    expect(e.document.querySelector('#rdkey')).toBeNull();e.open(profile('cd34','Other'));await settle();expect(e.get('#rdkey').value).toBe('');
  });
  it("places the key directly below the connected-account row before Categories without disconnecting OAuth",async()=>{
    const e=editor();e.open({...profile(),realDebrid:{username:'fixture_account'}});await settle();
    const app=e.get('#app'),disconnect=e.get('#rdoff'),keyCard=e.get('#rd-api-key');
    const connectionCard=disconnect.parent!;
    const categoryCard=app.children.find(el=>el.querySelectorAll('h3').some(h=>h.textContent==='Categories'))!;
    const keyIndex=app.children.indexOf(keyCard);
    expect(app.children.indexOf(connectionCard)).toBe(keyIndex-1);expect(app.children.indexOf(categoryCard)).toBe(keyIndex+1);
    e.get('#rdkey').value='fixture-only-connected-key-123';await e.get('[data-save]').onclick!();await e.get('[data-remove]').onclick!();
    expect(e.get('#rdoff')).toBe(disconnect);expect(connectionCard.isConnected).toBe(true);
    expect(e.calls.filter(c=>c.path.endsWith('/realdebrid')&&c.opts.method==='DELETE')).toEqual([]);
    expect(e.calls.filter(c=>c.path.endsWith('/api-key')&&c.opts.method).map(c=>c.opts.method)).toEqual(['PUT','DELETE']);
  });
  it("ignores a late credential mutation response after another profile opens",async()=>{
    let finish!:(value:unknown)=>void;const e=editor(()=>new Promise(r=>finish=r));e.open();await settle();
    const oldStatus=e.get('[data-status]');e.get('#rdkey').value='fixture-only-pending-key-123';const pending=e.get('[data-save]').onclick!();
    expect(e.get('#rdkey').value).toBe('');e.open(profile('cd34','Other'));await settle();
    const newStatus=e.get('[data-status]'),text=newStatus.textContent;expect(oldStatus.isConnected).toBe(false);
    finish({profileId:'ab12',revision:2,configured:false});await pending;await settle();
    expect(e.get('#pname').value).toBe('Other');expect(e.get('[data-status]')).toBe(newStatus);expect(newStatus.textContent).toBe(text);expect(e.get('#rdkey').value).toBe('');
    expect(e.calls.filter(c=>c.opts.method==='PUT'&&c.path.endsWith('/api-key')).map(c=>c.path)).toEqual(['/api/admin/profiles/ab12/realdebrid/api-key']);
  });
});

describe("category list folding",()=>{
  const live={live:{names:["News","Sports","Kids"]}};
  it("starts closed, opens on its header, and remembers that in this browser",async()=>{
    const stored=new Map<string,string>();const e=editor(undefined,live,stored);e.open();await settle();
    const fold=()=>e.get('[data-catfold]');
    expect(fold().textContent).toBe('▸ Live TV: 3 categories');expect(e.document.querySelector('#catlist')).toBeNull();
    fold().onclick!();await settle();
    expect(fold().textContent).toBe('▾ Live TV: 3 categories');expect(e.get('#catlist').querySelectorAll('[data-cat]').length).toBe(3);
    expect(JSON.parse(stored.get('ftv_cats_open')!)).toEqual({live:true});
    const again=editor(undefined,live,stored);again.open();await settle();expect(again.get('#catlist').querySelectorAll('[data-cat]').length).toBe(3);
  });
  it("keeps hidden categories and order when saved with the list closed",async()=>{
    const e=editor(undefined,live);
    e.open({id:'ab12',name:'Home',data:{sources:[],extraGuides:[],hiddenCategories:['Kids'],categoryOrder:{live:['Sports','News','Kids']}}});await settle();
    expect(e.document.querySelector('#catlist')).toBeNull();await e.get('#save').onclick!();await settle();
    const sent=JSON.parse(e.calls.find(c=>c.path==='/api/admin/profiles/ab12'&&c.opts.method==='PUT')!.opts.body as string);
    expect(sent.data).toMatchObject({hiddenCategories:['Kids'],categoryOrder:{live:['Sports','News','Kids']}});
  });
});

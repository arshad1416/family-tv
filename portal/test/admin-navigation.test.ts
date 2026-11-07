import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';
import { ADMIN_PAGE } from '../src/admin-page';
const settle=async()=>{for(let i=0;i<30;i++)await Promise.resolve();};
function mount() {
  const nodes:any={}; const node=(s:string)=>nodes[s]??=({innerHTML:'',value:'',focus(){},classList:{toggle(){}}});
  const nav=['devices','profiles','lines','addons'].map(tab=>({dataset:{tab},classList:{toggle(){}},onclick:null as any}));
  const pending:{path:string,resolve:(v:any)=>void,reject:(e:any)=>void}[]=[];
  const context:any={location:{origin:'https://portal.test',hash:''},document:{querySelector:node,querySelectorAll:(s:string)=>s==='nav button'?nav:[]},localStorage:{getItem:()=> 'admin',setItem(){},removeItem(){}},setInterval(){},fetch:(path:string)=>new Promise((resolve,reject)=>pending.push({path,resolve:(data:any)=>resolve({ok:true,status:200,json:async()=>data}),reject})),console};
  runInNewContext(ADMIN_PAGE.match(/<script>([\s\S]*?)<\/script>/)![1],context);
  return {nodes,nav,pending};
}
describe('admin navigation',()=>{
  it.each(['devices','lines','addons'])('ignores delayed %s content after switching to profiles',async(tab)=>{
    const h=mount(); h.pending.shift()!.resolve({OK:{}}); await settle();
    if(tab!=='devices'){h.nav.find(n=>n.dataset.tab===tab)!.onclick();await settle();}
    const old=h.pending.splice(0);
    h.nav[1].onclick();await settle(); h.pending.splice(0).forEach(p=>p.resolve({profiles:[]}));await settle();
    const current=h.nodes['#app'].innerHTML; expect(current).toContain('New profile');
    old.forEach(p=>p.resolve(p.path.endsWith('/devices')?{devices:[]}:p.path.endsWith('/profiles')?{profiles:[]}:p.path.endsWith('/panel')?{configured:false}:p.path.endsWith('/lines')?{lines:[]}:{addons:[],purchases:[],memberships:{}}));await settle();
    expect(h.nodes['#app'].innerHTML).toBe(current);
  });
  it('ignores an obsolete request error after switching tabs',async()=>{
    const h=mount();h.pending.shift()!.resolve({OK:{}});await settle();const old=h.pending.splice(0);
    h.nav[1].onclick();await settle();h.pending.splice(0).forEach(p=>p.resolve({profiles:[]}));await settle();const current=h.nodes['#app'].innerHTML;
    old.forEach(p=>p.reject(new Error('old request failed')));await settle();expect(h.nodes['#app'].innerHTML).toBe(current);
  });
  it('ignores an older refresh within the same tab',async()=>{
    const h=mount();h.pending.shift()!.resolve({OK:{}});await settle();const old=h.pending.splice(0);
    h.nav[0].onclick();await settle();h.pending.splice(0).forEach(p=>p.resolve(p.path.endsWith('/devices')?{devices:[]}:{profiles:[]}));await settle();
    const current=h.nodes['#app'].innerHTML;
    old.forEach(p=>p.resolve(p.path.endsWith('/devices')?{devices:[]}:{profiles:[{id:'stale',name:'Stale profile'}]}));await settle();
    expect(h.nodes['#app'].innerHTML).toBe(current);expect(current).not.toContain('Stale profile');
  });
  it('ignores delayed profiles after switching to devices',async()=>{
    const h=mount();h.pending.shift()!.resolve({OK:{}});await settle();h.pending.splice(0).forEach(p=>p.resolve(p.path.endsWith('/devices')?{devices:[]}:{profiles:[]}));await settle();
    h.nav[1].onclick();await settle();const old=h.pending.splice(0);
    h.nav[0].onclick();await settle();h.pending.splice(0).forEach(p=>p.resolve(p.path.endsWith('/devices')?{devices:[]}:{profiles:[]}));await settle();const current=h.nodes['#app'].innerHTML;
    old.forEach(p=>p.resolve({profiles:[]}));await settle();expect(h.nodes['#app'].innerHTML).toBe(current);expect(current).toContain('Add a TV');
  });

});

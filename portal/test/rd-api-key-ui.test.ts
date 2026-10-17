import { runInNewContext } from "node:vm";
import { describe, expect, it } from "vitest";
import { ADMIN_PAGE } from "../src/admin-page";
function mount(api: (path: string, opts?: any) => Promise<unknown>, confirm = () => true) {
  const nodes = Object.fromEntries(["input", "[data-status]", "[data-preview]", "[data-save]", "[data-remove]", "[data-refresh]"].map(k => [k, { value: "", textContent: "", disabled: false, onclick: null as any }]));
  const card = { innerHTML: "", isConnected: true, querySelector: (s: string) => nodes[s] };
  const source = ADMIN_PAGE.match(/^function mountRdApiKey\(draft, card\) \{[\s\S]*?^\}/m)?.[0];
  expect(source, "credential control exists").toBeTruthy();
  runInNewContext(source + '\nmountRdApiKey({id:"ab12"},card)', { card, api, confirm });
  return { nodes, card };
}
const settle = async () => { for(let i=0;i<10;i++) await Promise.resolve(); };
describe("portal masked credential control", () => {
  it("starts blank/masked, uses status only and clears before saving without echoing", async () => {
    const calls: any[]=[];
    const {nodes,card}=mount(async(path,opts)=>{calls.push([path,opts]);return {profileId:"ab12",revision:opts?2:1,configured:!!opts,maskedPreview:opts?"fixt********-123":null};});
    await settle(); expect(card.innerHTML).toContain('type="password"'); expect(card.innerHTML).toContain('autocomplete="off"');
    expect(nodes.input.value).toBe(""); nodes.input.value="fixture-only-ui-secret-123";
    const pending=nodes['[data-save]'].onclick(); expect(nodes.input.value).toBe(""); await pending;
    expect(calls[1][0]).toBe('/api/admin/profiles/ab12/realdebrid/api-key');
    expect(JSON.parse(calls[1][1].body)).toEqual({revision:1,apiKey:"fixture-only-ui-secret-123"});
    expect(card.innerHTML+nodes['[data-status]'].textContent).not.toContain("fixture-only-ui-secret-123");
    expect(nodes['[data-status]'].textContent).toContain("saved");expect(nodes['[data-preview]'].textContent).toBe("fixt********-123");
  });
  it("requires explicit removal confirmation and does not echo server errors",async()=>{
    const calls:any[]=[];const {nodes}=mount(async(path,opts)=>{calls.push(opts);if(opts)throw new Error('fixture-only-ui-secret-123');return{profileId:"ab12",revision:4,configured:true,maskedPreview:"fixt********-123"};});
    await settle();await nodes['[data-remove]'].onclick();
    expect(JSON.parse(calls[1].body)).toEqual({revision:4});expect(calls[1].method).toBe('DELETE');
    expect(nodes['[data-status]'].textContent).not.toContain('fixture-only-ui-secret-123');
    const canceled=mount(async()=>({profileId:"ab12",revision:4,configured:true,maskedPreview:"fixt********-123"}),()=>false);await settle();await canceled.nodes['[data-remove]'].onclick();expect(canceled.nodes['[data-status]'].textContent).toContain('saved');
  });
  it("ignores status responses after navigating away and never rebuilds the profile form",async()=>{
    let finish:any;const {nodes,card}=mount(()=>new Promise(r=>finish=r));const html=card.innerHTML;
    card.isConnected=false;finish({revision:9,configured:true});await settle();
    expect(nodes['[data-status]'].textContent).not.toContain('saved');expect(card.innerHTML).toBe(html);
  });
});

describe("saved preview response validation",()=>{
  it.each([{profileId:"cd34",revision:1,configured:true,maskedPreview:"ABCD********WXYZ"},{profileId:"ab12",revision:-1,configured:true,maskedPreview:"ABCD********WXYZ"},{profileId:"ab12",revision:1,configured:true,maskedPreview:"fixture-full-secret-123"}])("rejects malformed or wrong-profile responses",async(result)=>{const {nodes}=mount(async()=>result);await settle();expect(nodes['[data-preview]'].textContent).toBe("");expect(nodes['[data-save]'].disabled).toBe(true);});
  it("rejects a write response with an unexpected revision",async()=>{const {nodes}=mount(async(_,opts)=>({profileId:"ab12",revision:opts?1:1,configured:true,maskedPreview:"ABCD********WXYZ"}));await settle();nodes.input.value="fixture-only-secret-123";await nodes['[data-save]'].onclick();expect(nodes['[data-preview]'].textContent).toBe("");expect(nodes['[data-save]'].disabled).toBe(true);});
});

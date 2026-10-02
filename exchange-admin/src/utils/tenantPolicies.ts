export type PolicySnapshot = { tenantId:number; tenantName:string; status:string; policyVersion:number; features:Record<string,boolean>; configs:{key:string;locked:boolean;denied:boolean;version:number}[]; supportChannel:string|null }
export function configEditable(policy:PolicySnapshot|null,key:string):boolean {
 if(!policy)return false
 const config=policy.configs.find(row=>row.key===key)
 if(config?.locked||config?.denied)return false
 if(key==='customer.service.link'&&(policy.supportChannel==='internal'||!policy.features.external_support))return false
 return true
}

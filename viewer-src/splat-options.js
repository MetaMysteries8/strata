import {SceneFormat} from '@mkkellogg/gaussian-splats-3d';
export function splatSource(id){
 if(!/^[a-f0-9-]{36}$/.test(id))throw new Error('Invalid local scan ID');
 return {url:'/scans/'+id+'/model.ply',options:{format:SceneFormat.Ply,splatAlphaRemovalThreshold:1,showLoadingUI:false,progressiveLoad:false}};
}

'use strict';
window.StrataProgress=(()=>{
 const labels={uploading:'Uploading capture',queued:'Waiting for GPU worker',loading:'Loading capture on worker',alignment:'Matching photos',undistorting:'Preparing depth',depth:'Estimating depth',fusion:'Fusing depth',meshing:'Building mesh',training:'Training splats',exporting:'Validating export',downloading:'Downloading models',complete:'Ready on this phone',reconnecting:'Reconnecting',paused:'Monitoring paused'};
 function duration(milliseconds){const s=Math.max(0,Math.floor(milliseconds/1000));return s<60?s+'s':s<3600?Math.floor(s/60)+'m '+s%60+'s':Math.floor(s/3600)+'h '+Math.floor(s%3600/60)+'m'}
 function describe(p){
  const s=p.state||{};
  let stage=s.stage||s.serverPhase||s.phase||'ready';
  if(s.phase==='complete')stage='complete';
  if(s.phase==='failed'&&p.hasRemote&&!p.remoteDone)stage='paused';
  const mesh=p.remoteMode==='mesh'||p.remoteMode==='both';
  const splat=p.remoteMode!=='mesh';
  const route=['uploading','queued','loading',...(mesh?['alignment','undistorting','depth','fusion','meshing']:[]),...(splat?['training','exporting']:[]),'downloading','complete'];
  const failedStages=[],skippedStages=[];
  for(const [kind,o] of Object.entries(s.outcomes||{})){if(o.status!=='failed')continue;const steps=kind==='mesh'?['alignment','undistorting','depth','fusion','meshing']:['training','exporting'];const index=Math.max(0,steps.indexOf(o.stage));failedStages.push(steps[index]);skippedStages.push(...steps.slice(index+1))}
  return {failedStages,skippedStages,stage,label:s.partial&&stage==='complete'?'Ready · partial result':labels[stage]||stage.replaceAll('_',' '),route,index:route.indexOf(stage),submitted:Number(s.serverCreated)||0,contact:Number(s.lastContact)||0,heartbeat:Number(s.workerHeartbeat)||0,engine:String(s.engine||''),engineElapsed:Number(s.engineElapsed)||0,stageStarted:Number(s.stageStarted)||0,queued:stage==='queued',labels};
 }
 function workerMessage(stage,heartbeat,now=Date.now()){
  if(['complete','downloading','paused','cancelled','failed'].includes(stage))return '';
  if(!heartbeat)return 'No worker heartbeat has been reported. Older servers may not provide it.';
  return now-heartbeat>90000?'Worker heartbeat is over 90 seconds old. Check provider logs and metrics; this alone does not prove failure.':'Worker heartbeat shows reporting activity, not GPU utilization or completion percentage.';
 }
 function atLeast(version,minimum){const v=String(version||'').split('.').map(Number);if(v.length!==3||v.some(x=>!Number.isInteger(x)||x<0))return false;for(let i=0;i<3;i++){if(v[i]>minimum[i])return true;if(v[i]<minimum[i])return false}return true}
 return {describe,duration,atLeast,workerMessage};
})();

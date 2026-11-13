import * as THREE from 'three';
import {Viewer,SceneRevealMode} from '@mkkellogg/gaussian-splats-3d';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {splatSource} from './splat-options.js';
const $=id=>document.getElementById(id),params=new URLSearchParams(location.search),id=params.get('id'),meshMode=params.get('kind')==='mesh';
let viewer,renderer,scene,camera,controls,mesh,ready=false,orbit=false,light=false,zUp=false;
const center=new THREE.Vector3();let radius=1;
const buttons=['fit','points','orbit','background','up'];
buttons.forEach(id=>$(id).disabled=true);
function fail(error){ready=false;buttons.forEach(id=>$(id).disabled=true);$('message').hidden=false;$('message').classList.add('error');$('messageTitle').textContent='This model could not be opened';$('messageText').textContent=error?.message||String(error);$('messageHint').textContent='Your original capture is kept. Return to the scan to retry processing or export its dataset.'}
window.addEventListener('unhandledrejection',event=>fail(event.reason));
$('back').onclick=()=>window.Strata?.back();$('export').onclick=()=>window.Strata?.export(id,meshMode?'glb':'model');
function fit(){if(!ready)return;controls.target.copy(center);camera.up.set(0,zUp?0:1,zUp?1:0);camera.position.copy(center).add(zUp?new THREE.Vector3(radius*.8,-radius*1.8,radius*.8):new THREE.Vector3(radius*.8,radius*.45,radius*1.8));camera.near=Math.max(.002,radius/2000);camera.far=Math.max(100,radius*100);camera.updateProjectionMatrix();controls.update()}
$('fit').onclick=fit;$('orbit').onclick=()=>{orbit=!orbit;$('orbit').classList.toggle('active',orbit)};$('up').onclick=()=>{zUp=!zUp;$('up').textContent=zUp?'Z up':'Y up';fit()};
$('points').onclick=()=>{if(!ready)return;if(meshMode){const on=!$('points').classList.contains('active');mesh.traverse(o=>{if(o.isMesh){for(const m of [].concat(o.material))m.wireframe=on}});$('points').classList.toggle('active',on)}else{const on=!viewer.splatMesh.getPointCloudModeEnabled();viewer.splatMesh.setPointCloudModeEnabled(on);$('points').classList.toggle('active',on)}};
$('background').onclick=()=>{light=!light;renderer?.setClearColor(light?0xe9eaed:0x18181c,1)};$('scale').oninput=()=>{if(ready&&!meshMode)viewer.splatMesh.setSplatScale(Number($('scale').value))};
function done(detail){ready=true;buttons.forEach(id=>$(id).disabled=false);fit();$('message').hidden=true;$('detail').textContent=detail}
function startLoop(){let previous=performance.now();function frame(now){requestAnimationFrame(frame);if(!ready||document.hidden){previous=now;return}const delta=Math.min(.1,(now-previous)/1000);previous=now;if(orbit){const v=camera.position.clone().sub(controls.target);v.applyAxisAngle(camera.up,delta*.15);camera.position.copy(controls.target).add(v)}controls.update();if(meshMode)renderer.render(scene,camera)}requestAnimationFrame(frame)}
try{
 const source=splatSource(id);const projects=window.Strata?JSON.parse(Strata.projects()):[];const project=projects.find(p=>p.id===id);if(project)$('title').textContent=project.name;
 $('mode').textContent=meshMode?'3D mesh':'Gaussian splat';
 if(meshMode){
  $('points').textContent='Wireframe';$('settings').hidden=true;
  scene=new THREE.Scene();renderer=new THREE.WebGLRenderer({antialias:true});renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));renderer.setClearColor(0x18181c,1);$('scene').append(renderer.domElement);camera=new THREE.PerspectiveCamera(55,1,.01,1000);controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;
  scene.add(new THREE.HemisphereLight(0xffffff,0x646477,2.5));const sun=new THREE.DirectionalLight(0xffffff,2);sun.position.set(3,5,4);scene.add(sun);
  function resize(){const w=$('scene').clientWidth,h=$('scene').clientHeight;renderer.setSize(w,h);camera.aspect=w/h;camera.updateProjectionMatrix()}new ResizeObserver(resize).observe($('scene'));resize();
  new GLTFLoader().load('/scans/'+id+'/mesh.glb',gltf=>{mesh=gltf.scene;scene.add(mesh);const box=new THREE.Box3().setFromObject(mesh);if(box.isEmpty())throw new Error('The GLB contains no surface geometry');box.getCenter(center);radius=Math.max(.02,box.getSize(new THREE.Vector3()).length()/2);let vertices=0,triangles=0;mesh.traverse(o=>{if(o.isMesh){vertices+=o.geometry.attributes.position.count;triangles+=(o.geometry.index?o.geometry.index.count:o.geometry.attributes.position.count)/3}});done(triangles.toLocaleString()+' triangles · '+vertices.toLocaleString()+' vertices')},undefined,fail);
 }else{
  viewer=new Viewer({rootElement:$('scene'),cameraUp:[0,1,0],initialCameraPosition:[2,1,3],initialCameraLookAt:[0,0,0],sharedMemoryForWorkers:false,gpuAcceleratedSort:false,integerBasedSort:false,enableSIMDInSort:true,sphericalHarmonicsDegree:2,halfPrecisionCovariancesOnGPU:true,ignoreDevicePixelRatio:true,sceneRevealMode:SceneRevealMode.Instant,inMemoryCompressionLevel:0,freeIntermediateSplatData:true});
  renderer=viewer.renderer;camera=viewer.camera;controls=viewer.controls;renderer.setClearColor(0x18181c,1);
  viewer.addSplatScene(source.url,source.options).then(()=>{const count=viewer.splatMesh.getSplatCount();if(!count)throw new Error('No renderable Gaussians');const axes=[[],[],[]],v=new THREE.Vector3(),step=Math.max(1,Math.floor(count/12000));for(let i=0;i<count;i+=step){viewer.splatMesh.getSplatCenter(i,v);if(Number.isFinite(v.x+v.y+v.z)){axes[0].push(v.x);axes[1].push(v.y);axes[2].push(v.z)}}if(!axes[0].length)throw new Error('No finite Gaussian positions');const sizes=[];for(let i=0;i<3;i++){const a=axes[i].sort((a,b)=>a-b),lo=a[Math.floor(a.length*.02)],hi=a[Math.min(a.length-1,Math.floor(a.length*.98))];center.setComponent(i,(lo+hi)/2);sizes.push(hi-lo)}radius=Math.max(.02,new THREE.Vector3(...sizes).length()/2);viewer.start();done(count.toLocaleString()+' Gaussians · SH2 viewing / full coefficients exported')}).catch(fail);
 }
 startLoop();document.addEventListener('visibilitychange',()=>{if(viewer&&ready){if(document.hidden)viewer.stop();else viewer.start()}});window.addEventListener('pagehide',()=>{ready=false;viewer?.dispose();controls?.dispose();if(meshMode){scene?.traverse(o=>{o.geometry?.dispose();if(o.material)for(const m of [].concat(o.material))m.dispose()});renderer?.dispose()}});
}catch(error){fail(error)}

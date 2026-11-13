import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import * as THREE from 'three';
import {Viewer,SceneFormat,LoaderUtils} from '@mkkellogg/gaussian-splats-3d';
import {splatSource} from '../splat-options.js';
// The CPU loader uses window.setTimeout for yielding; this is not a WebGL/rendering test.
globalThis.window={setTimeout,clearTimeout};
const names=['x','y','z','f_dc_0','f_dc_1','f_dc_2','opacity','scale_0','scale_1','scale_2','rot_0','rot_1','rot_2','rot_3'];
const header=Buffer.from('ply\nformat binary_little_endian 1.0\nelement vertex 8\n'+names.map(n=>'property float '+n+'\n').join('')+'end_header\n');
const data=Buffer.alloc(8*names.length*4);
for(let i=0;i<8;i++){const row=[i*.1,0,-1,0,0,0,2,-4,-4,-4,1,0,0,0];row.forEach((n,j)=>data.writeFloatLE(n,(i*row.length+j)*4))}
const ply=Buffer.concat([header,data]);
test('query-bearing PLY URLs reproduce the original extension inference failure',()=>{assert.equal(LoaderUtils.sceneFormatFromPath('/model.ply?id=scan'),null);const shim={updateError:e=>e};assert.throws(()=>Viewer.prototype.downloadSplatSceneToSplatBuffer.call(shim,'/model.ply?id=scan'),/File format not supported/)});
test('explicit PLY format loads and parses actual Gaussian bytes even with a query string',async t=>{
 const server=http.createServer((req,res)=>{res.writeHead(200,{'Content-Length':ply.length});res.end(ply)});await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>server.close());
 const source=splatSource('00000000-0000-0000-0000-000000000001');assert.equal(source.options.format,SceneFormat.Ply);assert.equal(LoaderUtils.sceneFormatFromPath(source.url),SceneFormat.Ply);
 const shim={optimizeSplatData:false,inMemoryCompressionLevel:0,sphericalHarmonicsDegree:0,updateError:e=>e};
 const buffer=await Viewer.prototype.downloadSplatSceneToSplatBuffer.call(shim,'http://127.0.0.1:'+server.address().port+'/model.ply?id=scan',1,undefined,false,undefined,source.options.format);
 assert.equal(buffer.getSplatCount(),8);const point=new THREE.Vector3();buffer.getSplatCenter(7,point);assert.ok(Math.abs(point.x-.7)<1e-5);assert.equal(point.z,-1);
});
test('rejects an invalid scan identifier before constructing a local route',()=>assert.throws(()=>splatSource('../private'),/Invalid/));

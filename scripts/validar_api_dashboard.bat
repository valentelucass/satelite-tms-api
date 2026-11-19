@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
node -e "const a=require('./deploy/clientes/ecosystem.config.js').apps.find(p=>p.name==='api-integracoes-19090' && p.namespace==='satelite-tms');if(!a)process.exit(1);console.log(a.namespace+'/'+a.name)"
exit /b %errorlevel%

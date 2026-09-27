// 在仓库根目录运行；Ajv 仅安装到本模块忽略的 target/schema-validation 目录。
const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const root = path.resolve(__dirname, '../../../../..');
const ajvRoot = path.join(root, 'src/cloud-plugin-core/target/schema-validation/node_modules/ajv');
const Ajv2020 = require(path.join(ajvRoot, 'dist/2020')).default;
const schema = JSON.parse(fs.readFileSync(path.join(root, 'schemas/plugin-manifest.schema.json'), 'utf8'));
const example = JSON.parse(fs.readFileSync(path.join(root, 'schemas/examples/plugin-manifest.paddleocr.example.json'), 'utf8'));
const validate = new Ajv2020({strict: true, allErrors: true}).compile(schema);
assert.equal(validate(example), true, JSON.stringify(validate.errors));
const mutations = [
    x => { x.enabled = true; },
    x => { x.spiVersion = 2; },
    x => { x.providers[0].documentParsing.supportsFormulas = false; },
    x => { x.providers[0].documentParsing.defaultOptions.recognizeTables = 'false'; },
    x => { x.providers[0].documentParsing.defaultOptions.recognizeTables = null; },
    x => { x.runtimeModes.push('DOCKER'); },
    x => { x.providers[0].documentParsing.mediaTypes = ['image/*']; },
    x => { x.providers[0].capability = 'email'; }
];
for (const mutate of mutations) {
    const value = structuredClone(example);
    mutate(value);
    assert.equal(validate(value), false, 'invalid fixture unexpectedly accepted');
}
console.log('Ajv ' + require(path.join(ajvRoot, 'package.json')).version
    + ': strict Draft 2020-12 schema compile, valid example and 8 invalid fixtures passed.');

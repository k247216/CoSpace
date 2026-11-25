// 无测试框架依赖：验证协议编排和重试窗口，文件/HTTP集成另由浏览器检查。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function main() {
    const source = fs.readFileSync(path.join(__dirname, '../src/utils/resumable-upload.js'), 'utf8')
    const {uploadFile} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))
    const tests = []
    const test = (name, fn) => tests.push([name, fn])
    function fixture(size = 5) {
        const data = new Map(), calls = [], updates = []
        const item = {file: new File([new Uint8Array(size)], 'same.bin'), parentId: '100', md5: null, uploadId: null}
        const task = {uploadId: 'task-1', state: 'UPLOADING', chunkSize: 2, totalChunks: Math.max(1, Math.ceil(size / 2)), uploadedChunks: [], expiresAt: '2999-01-01'}
        const store = {get: k => data.get(k), set: (k, v) => data.set(k, v), remove: k => data.delete(k)}
        let hashes = 0
        const api = {
            instant: async () => {calls.push('instant'); throw {status: 1, message: '秒传未命中，请上传文件'}},
            initialize: async () => {calls.push('initialize'); return {data: {...task}}},
            status: async () => {calls.push('status'); return {data: {...task, uploadedChunks: [...task.uploadedChunks]}}},
            chunk: async (id, index, blob) => {calls.push(['chunk', index, blob.size]); task.uploadedChunks.push(index)},
            complete: async () => {calls.push('complete'); return {data: {...task, state: 'COMPLETED'}}}
        }
        const options = {api, store, hashFile: async () => {hashes++; return 'md5'}, update: fields => {updates.push(fields); Object.assign(item, fields)}}
        return {item, task, api, options, data, calls, updates, hashCount: () => hashes}
    }
    function deferred() {
        let resolve, reject
        const promise = new Promise((yes, no) => {resolve = yes; reject = no})
        return {promise, resolve, reject}
    }
    async function until(check) {
        for (let turn = 0; turn < 50; turn++) {
            if (check()) return
            await Promise.resolve()
        }
        assert.fail('异步上传未到达预期阶段')
    }
    test('最多三个分片并发，乱序返回后全部成功才合并', async () => {
        const f = fixture(13), requests = new Map(), finished = new Set()
        let active = 0, peak = 0
        f.api.chunk = async (id, index, blob) => {
            active++; peak = Math.max(peak, active)
            const gate = deferred(); requests.set(index, gate)
            assert.equal(blob.size, index === 6 ? 1 : 2)
            await gate.promise
            active--; finished.add(index)
        }
        const complete = f.api.complete
        f.api.complete = async () => {
            assert.equal(active, 0); assert.equal(finished.size, 7)
            return complete()
        }
        const uploading = uploadFile(f.item, f.options)
        await until(() => requests.size === 3)
        assert.deepEqual([...requests.keys()], [0, 1, 2])
        for (const index of [2, 1, 3, 0, 5, 4, 6]) {
            await until(() => requests.has(index))
            assert.equal(f.calls.includes('complete'), false)
            requests.get(index).resolve()
        }
        await uploading
        assert.equal(peak, 3); assert.equal(f.item.done, true)
    })
    test('一片失败停止派发，等待在途请求后报错，重试只补缺片', async () => {
        const f = fixture(12), requests = new Map(), send = f.api.chunk
        f.api.chunk = async (...args) => {
            const gate = deferred(); requests.set(args[1], gate)
            await gate.promise
            return send(...args)
        }
        let settled = false
        const uploading = uploadFile(f.item, f.options).finally(() => {settled = true})
        const rejected = assert.rejects(uploading, /offline/)
        await until(() => requests.size === 3)
        requests.get(1).reject(new Error('offline'))
        // 让失败请求的处理先完成，另外两片仍在传输。
        for (let turn = 0; turn < 10; turn++) await Promise.resolve()
        assert.equal(settled, false)
        requests.get(0).resolve(); requests.get(2).resolve()
        await rejected
        assert.deepEqual([...requests.keys()], [0, 1, 2])
        assert.equal(f.calls.includes('complete'), false)
        assert.equal(f.data.size, 1)
        f.api.chunk = send
        await uploadFile(f.item, f.options)
        assert.deepEqual(f.calls.filter(Array.isArray).map(c => c[1]), [0, 2, 1, 3, 4, 5])
        assert.equal(f.item.done, true)
    })
    test('直传恢复依据服务端返回的已收分片，剩余字节跳过应用分片接口', async () => {
        const f = fixture(5)
        f.task.directUpload = true
        f.task.uploadedChunks = [0, 2]
        f.item.uploadId = f.task.uploadId
        f.api.chunk = async () => assert.fail('直传分片不能走应用入口')
        f.api.directChunk = async (id, index, blob) => {
            assert.equal(id, f.task.uploadId)
            f.calls.push(['directChunk', index, blob.size])
        }
        await uploadFile(f.item, f.options)
        assert.deepEqual(f.calls, ['status', ['directChunk', 1, 2], 'complete'])
    })
    test('进度汇总多个在途分片，收到响应后不重复计算', async () => {
        const f = fixture(12), requests = new Map()
        f.api.chunk = async (id, index, blob, progress) => {
            const gate = deferred(); requests.set(index, {gate, progress})
            await gate.promise
        }
        const uploading = uploadFile(f.item, f.options)
        await until(() => requests.size === 3)
        for (const {progress} of requests.values()) progress({loaded: 1, total: 2})
        assert.equal(f.item.percent, 23)
        for (const {progress} of requests.values()) progress({loaded: 2, total: 2})
        assert.equal(f.item.percent, 47)
        requests.get(0).gate.resolve()
        await until(() => requests.has(3))
        assert.equal(f.item.percent, 47)
        for (const index of [1, 2, 3, 4, 5]) {
            await until(() => requests.has(index))
            requests.get(index).gate.resolve()
        }
        await uploading
        const percentages = f.updates.filter(u => u.percent !== undefined).map(u => u.percent)
        assert.ok(percentages.every((value, index) => index === 0 || value >= percentages[index - 1]))
    })
    test('按后端大小切片，complete之前不报告成功', async () => {
        const f = fixture()
        const complete = f.api.complete
        f.api.complete = async () => {assert.notEqual(f.item.status, 'success'); assert.equal(f.item.percent, 99); return complete()}
        await uploadFile(f.item, f.options)
        assert.deepEqual(f.calls, ['instant', 'initialize', ['chunk', 0, 2], ['chunk', 1, 2], ['chunk', 2, 1], 'complete'])
        assert.equal(f.item.done, true); assert.equal(f.data.size, 0)
    })
    test('中途失败保留任务，重试查询后跳过已收分片', async () => {
        const f = fixture(), send = f.api.chunk
        let fail = true
        f.api.chunk = async (...args) => {if(args[1] === 1 && fail){fail = false; throw new Error('offline')} return send(...args)}
        await assert.rejects(uploadFile(f.item, f.options), /offline/)
        assert.equal(f.item.uploadId, 'task-1'); assert.equal(f.data.size, 1)
        await uploadFile(f.item, f.options)
        assert.equal(f.hashCount(), 1)
        assert.deepEqual(f.calls.filter(c => Array.isArray(c) && c[1] === 0), [['chunk', 0, 2]])
        assert.equal(f.calls.filter(c => c === 'initialize').length, 1)
    })
    test('刷新后重新选择文件，重新计算摘要并恢复缺片', async () => {
        const f = fixture(), send = f.api.chunk
        f.api.chunk = async (...args) => {if(args[1] === 1) throw new Error('refresh'); return send(...args)}
        await assert.rejects(uploadFile(f.item, f.options))
        f.item.md5 = null; f.item.uploadId = null; f.api.chunk = send
        await uploadFile(f.item, f.options)
        assert.equal(f.hashCount(), 2)
        assert.equal(f.calls.filter(c => c === 'initialize').length, 1)
        assert.equal(f.calls.filter(c => c === 'instant').length, 1)
    })
    test('FINALIZING任务直接重试收尾，不重传分片', async () => {
        const f = fixture(); f.item.uploadId = 'task-1'; f.task.state = 'FINALIZING'
        await uploadFile(f.item, f.options)
        assert.deepEqual(f.calls, ['status', 'complete'])
    })
    test('完成响应丢失后查询COMPLETED，不重复秒传或入库', async () => {
        const f = fixture()
        f.api.complete = async () => {f.task.state = 'COMPLETED'; throw new Error('response lost')}
        await assert.rejects(uploadFile(f.item, f.options))
        f.calls.length = 0
        await uploadFile(f.item, f.options)
        assert.deepEqual(f.calls, ['status']); assert.equal(f.item.done, true)
    })
    test('登录或网络错误不能当作秒传未命中继续上传', async () => {
        for (const error of [{status: 10, message: 'login'}, new Error('network')]) {
            const f = fixture(); f.api.instant = async () => {throw error}
            await assert.rejects(uploadFile(f.item, f.options))
            assert.deepEqual(f.calls, []); assert.equal(f.item.done, undefined)
        }
    })
    test('未返回COMPLETED不能报告成功，并保留任务供重试', async () => {
        const f = fixture(); f.api.complete = async () => ({data: {...f.task, state: 'FINALIZING'}})
        await assert.rejects(uploadFile(f.item, f.options), /尚未完成/)
        assert.equal(f.item.done, undefined); assert.equal(f.data.size, 1)
    })
    test('秒传成功不创建任务', async () => {
        const f = fixture(); f.api.instant = async () => {f.calls.push('instant')}
        await uploadFile(f.item, f.options)
        assert.deepEqual(f.calls, ['instant']); assert.equal(f.item.done, true)
    })
    test('空文件按协议发送一个零字节分片', async () => {
        const f = fixture(0); await uploadFile(f.item, f.options)
        assert.deepEqual(f.calls.filter(Array.isArray), [['chunk', 0, 0]])
    })
    test('初始化响应丢失后复用requestId，不重复尝试秒传', async () => {
        const f = fixture(), requests = []
        let first = true
        f.api.initialize = async input => {
            requests.push(input.requestId)
            if (first) { first = false; throw new Error('response lost') }
            return {data: {...f.task}}
        }
        await assert.rejects(uploadFile(f.item, f.options), /response lost/)
        await uploadFile(f.item, f.options)
        assert.equal(requests.length, 2); assert.equal(requests[0], requests[1])
        assert.equal(f.calls.filter(call => call === 'instant').length, 1)
        assert.equal(f.item.done, true)
    })
    test('刷新后初始化重放仍使用保存的requestId', async () => {
        const f = fixture(), requests = new Map(), ids = []
        f.options.store.getRequest = key => requests.get(key)
        f.options.store.setRequest = (key, id) => requests.set(key, id)
        let first = true
        f.api.initialize = async input => {
            ids.push(input.requestId)
            if (first) { first = false; throw new Error('response lost') }
            return {data: {...f.task}}
        }
        await assert.rejects(uploadFile(f.item, f.options))
        f.item.requestId = null
        await uploadFile(f.item, f.options)
        assert.equal(ids[0], ids[1])
    })
    test('空间不足不会继续初始化上传任务', async () => {
        const f = fixture(); f.api.instant = async () => {throw {status: 4101, message: '空间不足'}}
        await assert.rejects(uploadFile(f.item, f.options), error => error.status === 4101)
        assert.deepEqual(f.calls, [])
    })
    test('初始化被限流不自动重试，也不发送分片', async () => {
        const f = fixture(); let attempts = 0
        f.api.initialize = async () => {attempts++; throw {httpStatus: 429, retryAfterSeconds: 3, message: '请求过频'}}
        await assert.rejects(uploadFile(f.item, f.options), error => error.retryAfterSeconds === 3)
        assert.equal(attempts, 1); assert.equal(f.calls.some(Array.isArray), false)
    })
    test('取消先停止派发并等待在途请求，再释放服务端任务', async () => {
        const f = fixture(12), gates = [], ended = new Set()
        f.api.chunk = async (id, index) => {
            const gate = deferred(); gates.push(gate); await gate.promise; ended.add(index)
        }
        f.api.cancel = async () => {assert.equal(ended.size, 3); f.calls.push('cancel')}
        const uploading = uploadFile(f.item, f.options)
        await until(() => gates.length === 3)
        f.item.cancelRequested = true
        gates.forEach(gate => gate.resolve())
        await uploading
        assert.equal(gates.length, 3); assert.equal(f.item.cancelled, true)
        assert.equal(f.calls.includes('complete'), false); assert.equal(f.data.size, 0)
    })
    for (const [name, run] of tests) {await run(); console.log('PASS ' + name)}
    console.log(tests.length + ' upload flow checks passed')
}
main().catch(error => {console.error(error); process.exitCode = 1})

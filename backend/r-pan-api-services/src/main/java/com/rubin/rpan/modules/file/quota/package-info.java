/** 用户逻辑空间账户与上传预占。service加入业务事务，model表达账户及预占状态，config配置默认容量。 容量不等于物理磁盘用量；释放用户空间与MQ删除存储内容分别提交。 */
package com.rubin.rpan.modules.file.quota;

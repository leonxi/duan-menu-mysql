package com.xiaoji.duan.aad.service.db;

public class CreateTable extends AbstractSql {

	public CreateTable() {
		initDdl();
	}

	private void initDdl() {

		ddl.add("" +
				"CREATE TABLE IF NOT EXISTS `aad_menus` (" +
				"		  `UNIONID` varchar(36) NOT NULL," +
				"		  `SUBDOMAIN` varchar(64) NOT NULL," +
				"		  `MENU_ID` int(8) NOT NULL," +
				"		  `MENU_PARENT_ID` int(8) NOT NULL," +
				"		  `MENU_NAME` varchar(64) NOT NULL," +
				"		  `MENU_ACTION` varchar(1024) NOT NULL," +
				"		  `MENU_POPUP_ID` varchar(64) DEFAULT ''," +
				"		  `MENU_ORDER` int(8) DEFAULT 0," +
				"		  PRIMARY KEY (`UNIONID`)" +
				") ENGINE=InnoDB DEFAULT CHARSET=utf8;" +
				"");

	}
}

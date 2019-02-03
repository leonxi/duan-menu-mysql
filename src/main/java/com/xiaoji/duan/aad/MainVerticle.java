package com.xiaoji.duan.aad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.util.StringUtils;

import com.xiaoji.duan.aad.service.db.CreateTable;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.TemplateHandler;
import io.vertx.ext.web.templ.thymeleaf.ThymeleafTemplateEngine;

public class MainVerticle extends AbstractVerticle {

	private ThymeleafTemplateEngine thymeleaf = null;
	private JDBCClient mySQLClient = null;

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		JsonObject mySQLClientConfig = new JsonObject()
				.put("url", "jdbc:mysql://" + config().getString("mysql.host", "duan-mysql") + "/" + config().getString("mysql.database", "duan"))
				.put("user", config().getString("mysql.username", "duan"))
				.put("password", config().getString("mysql.password", "1234"))
				.put("autoReconnect", config().getBoolean("mysql.autoReconnect", true))
				.put("driver_class", "com.mysql.cj.jdbc.Driver");
		mySQLClient = JDBCClient.createShared(vertx, mySQLClientConfig);
		
		CreateTable ct = new CreateTable();
		List<String> ddls = ct.getDdl();

		for (String ddl : ddls) {
			mySQLClient.update(ddl, update -> {

				if (update.succeeded()) {
					System.out.println("ddl");
				} else {
					update.cause().printStackTrace(System.out);
				}
			});
		}

		thymeleaf = ThymeleafTemplateEngine.create(vertx);
		TemplateHandler templatehandler = TemplateHandler.create(thymeleaf);

		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		resolver.setSuffix(".html");
		resolver.setCacheable(false);
		resolver.setTemplateMode("HTML5");
		resolver.setCharacterEncoding("utf-8");
		thymeleaf.getThymeleafTemplateEngine().setTemplateResolver(resolver);

		Router router = Router.router(vertx);

		StaticHandler staticfiles = StaticHandler.create().setCachingEnabled(false).setWebRoot("static");
		router.route("/aad/static/*").handler(staticfiles);
		router.route("/aad").pathRegex("\\/.+\\.json").handler(staticfiles);
		router.route("/aad").pathRegex("\\/.+\\.js").handler(staticfiles);
		router.route("/aad").pathRegex("\\/.+\\.css").handler(staticfiles);
		router.route("/aad").pathRegex("\\/.+\\.map").handler(staticfiles);

		BodyHandler datahandler = BodyHandler.create();
		router.route("/aad").pathRegex("\\/*").handler(datahandler);

		router.route("/aad/menus/*").handler(datahandler);
		router.route("/aad/menus/:subdomain/currentmenus").handler(ctx -> this.currentmenus(ctx));
		router.route("/aad/menus/:subdomain/list").handler(ctx -> this.list(ctx));
		router.route("/aad/menus/:subdomain/save").handler(ctx -> this.save(ctx));
		router.route("/aad/menus/:subdomain/:unionId/delete").handler(ctx -> this.delete(ctx));

		router.route("/aad/index").handler(ctx -> this.index(ctx));
		
		router.route("/aad").pathRegex("\\/[^\\.]*").handler(templatehandler);

		HttpServerOptions option = new HttpServerOptions();
		option.setCompressionSupported(true);

		vertx.createHttpServer(option).requestHandler(router::accept).listen(8080, http -> {
			if (http.succeeded()) {
				startFuture.complete();
				System.out.println("HTTP server started on http://localhost:8080");
			} else {
				startFuture.fail(http.cause());
			}
		});
	}

	private void currentmenus(RoutingContext ctx) {
		String subdomain = ctx.request().getParam("subdomain");

		Future<List<JsonObject>> up = Future.future();
		List<Future<JsonObject>> futuresList = new LinkedList<>();
		Future<JsonObject> rootfuture = Future.future();

		mySQLClient.query("select * from aad_menus where subdomain='" + subdomain + "' and menuParentId=1 order by menuOrder asc", find -> {

			if (find.succeeded()) {
				ResultSet rs = find.result();
            	System.out.println(rs.getOutput());
				List<JsonObject> rootMenus = null;
				
				if (rs.getOutput() != null)
					rootMenus = rs.getOutput().getList();
				else
					rootMenus = new ArrayList<>();
				
				if (rootMenus != null && rootMenus.size() > 0) {
					rootfuture.complete(new JsonObject()
							.put("menu", rootMenus));

					for (JsonObject rootmenu : rootMenus) {
						Future<JsonObject> future = Future.future();
						JsonObject subquery = new JsonObject()
								.put("subdomain", subdomain)
								.put("menuParentId", rootmenu.getInteger("menuId"))
								.put("$or", new JsonArray()
										.add(new JsonObject().put("isdel", false))
										.add(new JsonObject().put("isdel", new JsonObject().put("$exists", false))));
						System.out.println(subquery.encode());

						mySQLClient.query("select * from aad_menus where subdomain='" + subdomain + "' and menuParentId=" + rootmenu.getInteger("menuId") + " order by menuOrder asc", findsub -> {

							if (findsub.succeeded()) {
				            	System.out.println(findsub.result().getOutput());
								future.complete(new JsonObject()
										.put("menu", rootmenu.getString("unionId"))
										.put("sub_menus", findsub.result().getOutput()));
							} else {
								future.fail(findsub.cause());
							}
						
						});

						futuresList.add(future);
					}
					
					CompositeFuture.all(Arrays.asList(futuresList.toArray(new Future[futuresList.size()])))
					.map(v -> futuresList.stream().map(Future::result).collect(Collectors.toList()))
					.compose(compose -> {
						up.complete(compose);
					}, up).completer();
				} else {
					CompositeFuture.all(Arrays.asList(futuresList.toArray(new Future[futuresList.size()])))
					.map(v -> futuresList.stream().map(Future::result).collect(Collectors.toList()))
					.compose(compose -> {
						up.complete(compose);
					}, up).completer();
				
					rootfuture.complete(new JsonObject());
				}
			} else {
				CompositeFuture.all(Arrays.asList(futuresList.toArray(new Future[futuresList.size()])))
				.map(v -> futuresList.stream().map(Future::result).collect(Collectors.toList()))
				.compose(compose -> {
					up.complete(compose);
				}, up).completer();

				rootfuture.fail(find.cause());
			}
		
		});
		
		futuresList.add(rootfuture);
		
		up.setHandler(ar -> {
			if (ar.succeeded()) {
				List<JsonObject> results = ar.result();
				
				JsonArray response = new JsonArray();
				JsonArray root = new JsonArray();
				JsonObject dispatch = new JsonObject();
				
				for (JsonObject ele : results) {
					if (!ele.containsKey("sub_menus")) {
						root = (JsonArray) ele.getValue("menu");
					} else {
						dispatch.put(ele.getString("menu"), ele.getValue("sub_menus"));
					}
				}
				
				for (int i = 0; i < root.size(); i ++) {
					JsonObject rootmenu = root.getJsonObject(i);
					
					JsonArray submenus = dispatch.getJsonArray(rootmenu.getString("unionId"));
					
					response.add(rootmenu
							.put("menuLevel", 1)
							.put("subMenus", submenus.size()));
					
					for (int j = 0; j < submenus.size(); j++) {
						JsonObject submenu = submenus.getJsonObject(j);
						
						response.add(submenu
								.put("menuLevel", 2)
								.put("subMenus", 0));
					}
				}
				
				ctx.response().putHeader("content-type", "application/json;charset=utf-8").end(new JsonObject().put("data", response).encode());
			} else {
				ctx.response().end("[]");
			}
		});
	}
	
	private void save(RoutingContext ctx) {
		String subdomain = ctx.request().getParam("subdomain");
		JsonObject data = ctx.getBodyAsJson();
		System.out.println(data.encode());

    if (null == data.getString("unionId") || "".equals(data.getString("unionId"))) {
          data.put("unionId", UUID.randomUUID().toString());
          data.put("menuId", Integer.valueOf(data.getString("menuId")));
          data.put("menuParentId", Integer.valueOf(data.getString("menuParentId")));
          data.put("menuOrder", Integer.valueOf(data.getString("menuOrder")));

          save(data);
          ctx.response().end("{}");
    } else {
    	mySQLClient.query("select * from aad_menus where unionId='" + data.getString("unionId") + "'", find -> {
            if (find.succeeded()) {
            	ResultSet rs = find.result();
            	System.out.println(rs.getOutput());
              JsonObject one = null;
              
              if (rs.getOutput() != null) {
            	  one = rs.getOutput().getJsonObject(0);
              }
              data.put("menuId", Integer.valueOf(data.getString("menuId")));
              data.put("menuParentId", Integer.valueOf(data.getString("menuParentId")));
              data.put("menuOrder", Integer.valueOf(data.getString("menuOrder")));

              JsonObject saveObject = null;
              if (one != null) {
                saveObject = one.mergeIn(data);
              } else {
                saveObject = data;
              }

              save(saveObject);
              ctx.response().end("{}");
            } else {
              ctx.response().end("{}");
            }
          
    	});
    }
	}
	
	private void list(RoutingContext ctx) {
		String subdomain = ctx.request().getParam("subdomain");
		JsonObject query = new JsonObject()
				.put("subdomain", subdomain)
				.put("$or", new JsonArray()
						.add(new JsonObject().put("isdel", false))
						.add(new JsonObject().put("isdel", new JsonObject().put("$exists", false))));
		System.out.println(query.encode());
		
		mySQLClient.query("select * from aad_menus where subdomain='" + subdomain + "'", find -> {

			if (find.succeeded()) {
				ResultSet rs = find.result();
				System.out.println(rs.getOutput());
				List<JsonObject> menus = null;
				
				if (rs.getOutput() != null)
					rs.getOutput().getList();
				else
					menus = new ArrayList<>();
				
				ctx.response().end(new JsonObject().put("data", menus).encode());
			} else {
				ctx.response().end(new JsonObject().put("data", new JsonArray()).encode());
			}
		
		});
	}
	
	private void delete(RoutingContext ctx) {
		String subdomain = ctx.request().getParam("subdomain");
		String unionId = ctx.request().getParam("unionId");
		System.out.println(subdomain + "/" + unionId + " delete");

		mySQLClient.query("select * from aad_menus where unionId='" + unionId + "'", find -> {

			if (find.succeeded()) {
				ResultSet rs = find.result();
            	System.out.println(rs.getOutput());
            	
				JsonObject one = null;
				
				if (rs.getOutput() != null)
					one = rs.getOutput().getJsonObject(0);
				
				if (one != null) {
					one.put("isdel", true);
					save(one);
					ctx.response().end("{}");
				} else {
					ctx.response().end("{}");
				}
				
			} else {
				ctx.response().end("{}");
			}
		
		});
	}

	private void save(JsonObject one) {
		String unionId = one.getString("unionId", "");

		if (StringUtils.isEmpty(unionId)) {

			JsonArray params = new JsonArray();
			params.add(UUID.randomUUID().toString());
			params.add(one.getString("subdomain"));
			params.add(one.getInteger("menuId"));
			params.add(one.getInteger("menuParentId"));
			params.add(one.getString("menuName"));
			params.add(one.getString("menuAction"));
			params.add(one.getString("menuPopupId"));
			params.add(one.getInteger("menuOrder"));

			mySQLClient.callWithParams("insert into aad_menus(UNIONID, SUBDOMAIN, MENU_ID, MENU_PARENT_ID, MENU_NAME, MENU_ACTION, MENU_POPUP_ID, MENU_ORDER) values(?, ?, ?, ?, ?, ?, ?, ?)",
					params,
					new JsonArray(),
					insert -> {});
		} else {
			JsonArray params = new JsonArray();
			params.add(one.getString("subdomain"));
			params.add(one.getInteger("menuId"));
			params.add(one.getInteger("menuParentId"));
			params.add(one.getString("menuName"));
			params.add(one.getString("menuAction"));
			params.add(one.getString("menuPopupId"));
			params.add(one.getInteger("menuOrder"));
			params.add(one.getString("unionId"));

			mySQLClient.callWithParams("update aad_menus set SUBDOMAIN=?, MENU_ID=?, MENU_PARENT_ID=?, MENU_NAME=?, MENU_ACTION=?, MENU_POPUP_ID=?, MENU_ORDER=? where UNIONID=?", params, new JsonArray(), update -> {});
		}
	}
	
	private void index(RoutingContext ctx) {
		ctx.next();
	}

}


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
import io.vertx.ext.asyncsql.MySQLClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.TemplateHandler;
import io.vertx.ext.web.templ.thymeleaf.ThymeleafTemplateEngine;

public class MainVerticle extends AbstractVerticle {

	private ThymeleafTemplateEngine thymeleaf = null;
	private SQLClient mySQLClient = null;

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		JsonObject mmClientConfig = new JsonObject()
				.put("host", config().getString("mysql.host", "192.168.0.61"))
				.put("port", config().getInteger("mysql.port", 3306))
				.put("username", config().getString("mysql.username", "duan"))
				.put("password", config().getString("mysql.password", "1234"))
				.put("database", config().getString("mysql.database", "duan"))
				.put("maxConnectionRetries", config().getInteger("mysql.maxConnectionRetries", -1))
				.put("connectionRetryDelay", config().getInteger("mysql.connectionRetryDelay", 10000));
		mySQLClient = MySQLClient.createShared(vertx, mmClientConfig);
		
		vertx.exceptionHandler(error -> {
			error.printStackTrace();
		});
		
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
		router.route("/aad/menus/:subdomain/currentmenus").produces("application/json").handler(ctx -> this.currentmenus(ctx));
		router.route("/aad/menus/:subdomain/list").produces("application/json").handler(ctx -> this.list(ctx));
		router.route("/aad/menus/:subdomain/save").produces("application/json").handler(ctx -> this.save(ctx));
		router.route("/aad/menus/:subdomain/:unionId/delete").produces("application/json").handler(ctx -> this.delete(ctx));

		router.route("/aad/index").handler(ctx -> this.index(ctx));

		router.route("/aad").pathRegex("\\/[^\\.]*").handler(templatehandler);

		HttpServerOptions option = new HttpServerOptions();
		option.setCompressionSupported(true);

		vertx.createHttpServer(option).requestHandler(router::accept).listen(80, http -> {
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

		mySQLClient.query(
				"SELECT UNIONID unionId, SUBDOMAIN subdomain, MENU_ID menuId, MENU_PARENT_ID menuParentId, MENU_NAME menuName, MENU_ACTION menuAction, MENU_POPUP_ID menuPopupId, MENU_ORDER menuOrder FROM AAD_MENUS WHERE SUBDOMAIN='" + subdomain + "' AND MENU_PARENT_ID=1 ORDER BY MENU_ORDER ASC",
				find -> {

					if (find.succeeded()) {
						ResultSet rs = find.result();
						System.out.println("currentmenus " + rs.getNumRows());
						List<JsonObject> rootMenus = null;

						if (rs.getNumRows() > 0)
							rootMenus = rs.getRows();
						else
							rootMenus = new ArrayList<>();

						if (rootMenus != null && rootMenus.size() > 0) {
							rootfuture.complete(new JsonObject().put("menu", rootMenus));

							for (JsonObject rootmenu : rootMenus) {
								Future<JsonObject> future = Future.future();
								JsonObject subquery = new JsonObject().put("subdomain", subdomain)
										.put("menuParentId", rootmenu.getInteger("menuId"))
										.put("$or", new JsonArray().add(new JsonObject().put("isdel", false)).add(
												new JsonObject().put("isdel", new JsonObject().put("$exists", false))));
								System.out.println(subquery.encode());

								mySQLClient.query(
										"SELECT UNIONID unionId, SUBDOMAIN subdomain, MENU_ID menuId, MENU_PARENT_ID menuParentId, MENU_NAME menuName, MENU_ACTION menuAction, MENU_POPUP_ID menuPopupId, MENU_ORDER menuOrder FROM AAD_MENUS WHERE SUBDOMAIN='" + subdomain + "' AND MENU_PARENT_ID="
												+ rootmenu.getInteger("menuId") + " ORDER BY MENU_ORDER ASC",
										findsub -> {

											if (findsub.succeeded()) {
												System.out.println(findsub.result().getRows());
												future.complete(
														new JsonObject().put("menu", rootmenu.getString("unionId"))
																.put("sub_menus", findsub.result().getRows()));
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

				for (int i = 0; i < root.size(); i++) {
					JsonObject rootmenu = root.getJsonObject(i);

					JsonArray submenus = dispatch.getJsonArray(rootmenu.getString("unionId"));

					response.add(rootmenu.put("menuLevel", 1).put("subMenus", submenus.size()));

					for (int j = 0; j < submenus.size(); j++) {
						JsonObject submenu = submenus.getJsonObject(j);

						response.add(submenu.put("menuLevel", 2).put("subMenus", 0));
					}
				}

				ctx.response().putHeader("content-type", "application/json;charset=utf-8")
						.end(new JsonObject().put("data", response).encode());
			} else {
				ctx.response().putHeader("content-type", "application/json;charset=utf-8")
						.end(new JsonObject().put("data", new JsonArray()).encode());
			}
		});
	}

	private void save(RoutingContext ctx) {
		String subdomain = ctx.request().getParam("subdomain");
		JsonObject data = ctx.getBodyAsJson();
		System.out.println(data.encode());

		try {
			if (null == data.getString("unionId") || "".equals(data.getString("unionId"))) {
				data.put("menuId", Integer.valueOf(data.getString("menuId")));
				data.put("menuParentId", Integer.valueOf(data.getString("menuParentId")));
				data.put("menuOrder", Integer.valueOf(data.getString("menuOrder")));
	
				vertx.executeBlocking((Future<JsonObject> future) -> {
					save(future, data);
				}, res -> {
					System.out.println("save result " + res);
					ctx.response().putHeader("content-type", "application/json;charset=utf-8").end("{}");	
				});
				
			} else {
				mySQLClient.query("SELECT UNIONID unionId, SUBDOMAIN subdomain, MENU_ID menuId, MENU_PARENT_ID menuParentId, MENU_NAME menuName, MENU_ACTION menuAction, MENU_POPUP_ID menuPopupId, MENU_ORDER menuOrder FROM AAD_MENUS WHERE UNIONID='" + data.getString("unionId") + "'", find -> {
					if (find.succeeded()) {
						ResultSet rs = find.result();
						System.out.println("save " + rs.getNumRows());
						JsonObject one = null;
	
						if (rs.getOutput() != null) {
							one = rs.getRows().get(0);
						}
						data.put("menuId", Integer.valueOf(data.getString("menuId")));
						data.put("menuParentId", Integer.valueOf(data.getString("menuParentId")));
						data.put("menuOrder", Integer.valueOf(data.getString("menuOrder")));
	
						JsonObject saveObject = new JsonObject();
						if (one != null) {
							saveObject.mergeIn(one.mergeIn(data));
						} else {
							saveObject.mergeIn(data);
						}
	
						vertx.executeBlocking((Future<JsonObject> future) -> {
							save(future, saveObject);
						}, res -> {
							ctx.response().putHeader("content-type", "application/json;charset=utf-8").end("{}");	
						});
						
					} else {
						find.cause().printStackTrace();
						ctx.response().putHeader("content-type", "application/json;charset=utf-8").end("{}");
					}
	
				});
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void list(RoutingContext ctx) {
		String subdomain = ctx.request().getParam("subdomain");
		JsonObject query = new JsonObject().put("subdomain", subdomain).put("$or",
				new JsonArray().add(new JsonObject().put("isdel", false))
						.add(new JsonObject().put("isdel", new JsonObject().put("$exists", false))));
		System.out.println("list " + subdomain);

		mySQLClient.query("SELECT UNIONID unionId, SUBDOMAIN subdomain, MENU_ID menuId, MENU_PARENT_ID menuParentId, MENU_NAME menuName, MENU_ACTION menuAction, MENU_POPUP_ID menuPopupId, MENU_ORDER menuOrder FROM AAD_MENUS WHERE SUBDOMAIN='" + subdomain + "'", find -> {
			System.out.println("===========");

			if (find.succeeded()) {
				ResultSet rs = find.result();
				System.out.println("list rows " + rs.getNumRows());
				List<JsonObject> menus = null;

				if (rs.getNumRows() > 0)
					menus = rs.getRows();
				else
					menus = new ArrayList<>();

				ctx.response().putHeader("content-type", "application/json;charset=utf-8")
						.end(new JsonObject().put("data", menus).encode());
			} else {
				ctx.response().putHeader("content-type", "application/json;charset=utf-8")
						.end(new JsonObject().put("data", new JsonArray()).encode());
			}

		});
	}

	private void delete(RoutingContext ctx) {
		String subdomain = ctx.request().getParam("subdomain");
		String unionId = ctx.request().getParam("unionId");
		System.out.println(subdomain + "/" + unionId + " delete");

		mySQLClient.query("SELECT UNIONID unionId, SUBDOMAIN subdomain, MENU_ID menuId, MENU_PARENT_ID menuParentId, MENU_NAME menuName, MENU_ACTION menuAction, MENU_POPUP_ID menuPopupId, MENU_ORDER menuOrder FROM AAD_MENUS WHERE UNIONID='" + unionId + "'", find -> {

			if (find.succeeded()) {
				ResultSet rs = find.result();
				System.out.println("delete " + rs.getNumRows());

				JsonObject one = null;

				if (rs.getNumRows() > 0)
					one = rs.getRows().get(0);

				if (one != null) {
					one.put("isdel", true);
					Future<JsonObject> future = Future.future();
					save(future, one);
					future.setHandler(ar -> {
						ctx.response().putHeader("content-type", "application/json;charset=utf-8").end("{}");
					});
				} else {
					ctx.response().putHeader("content-type", "application/json;charset=utf-8").end("{}");
				}

			} else {
				ctx.response().putHeader("content-type", "application/json;charset=utf-8").end("{}");
			}

		});
	}

	private void save(Future<JsonObject> future, JsonObject one) {
		String unionId = one.getString("unionId", "");

		if (StringUtils.isEmpty(unionId)) {
System.out.println("unionId is Empty");
try {
			JsonArray params = new JsonArray();
			params.add(UUID.randomUUID().toString());
			params.add(one.getString("subdomain"));
			params.add(one.getInteger("menuId"));
			params.add(one.getInteger("menuParentId"));
			params.add(one.getString("menuName"));
			params.add(one.getString("menuAction"));
			params.add(one.getString("menuPopupId", ""));
			params.add(one.getInteger("menuOrder"));
			System.out.println("insert params " + params);

			mySQLClient.updateWithParams(
					"insert into aad_menus(UNIONID, SUBDOMAIN, MENU_ID, MENU_PARENT_ID, MENU_NAME, MENU_ACTION, MENU_POPUP_ID, MENU_ORDER) values(?, ?, ?, ?, ?, ?, ?, ?);",
					params, insert -> {
						System.out.println("insert result");
						if (insert.failed()) {
							insert.cause().printStackTrace();
							future.fail(insert.cause());
						} else {
							System.out.println("insert.result() " + insert.result());
							future.complete(new JsonObject());
						}
					});
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			System.out.println("unionId is exist");
			
			boolean isdel = one.getBoolean("isdel", false);
			
			if (isdel) {
				mySQLClient.update("DELETE FROM AAD_MENUS WHERE UNIONID='" + unionId + "'", delete -> {
					System.out.println(unionId + " is deleted.");
					future.complete(new JsonObject());
				});
			} else {
				JsonArray params = new JsonArray();
				params.add(one.getString("subdomain"));
				params.add(one.getInteger("menuId"));
				params.add(one.getInteger("menuParentId"));
				params.add(one.getString("menuName"));
				params.add(one.getString("menuAction"));
				params.add(one.getString("menuPopupId", ""));
				params.add(one.getInteger("menuOrder"));
				params.add(one.getString("unionId"));

				mySQLClient.updateWithParams(
						"update aad_menus set SUBDOMAIN=?, MENU_ID=?, MENU_PARENT_ID=?, MENU_NAME=?, MENU_ACTION=?, MENU_POPUP_ID=?, MENU_ORDER=? where UNIONID=?",
						params, update -> {
							if (update.failed()) {
								update.cause().printStackTrace();
								future.fail(update.cause());
							} else {
								System.out.println(update.result());
								future.complete(new JsonObject());
							}
						});
			}
			
		}
	}

	private void index(RoutingContext ctx) {
		ctx.next();
	}

}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ru.apertum.qsystem.bslogistic;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import ru.apertum.qsystem.common.CustomerState;
import ru.apertum.qsystem.common.QLog;
import ru.apertum.qsystem.common.model.QCustomer;
import ru.apertum.qsystem.extra.IChangeCustomerStateEvent;
import ru.apertum.qsystem.extra.ISelectNextService;
import ru.apertum.qsystem.server.ServerProps;
import ru.apertum.qsystem.server.model.QPlanService;
import ru.apertum.qsystem.server.model.QService;
import ru.apertum.qsystem.server.model.QServiceTree;
import ru.apertum.qsystem.server.model.QUser;
import ru.apertum.qsystem.server.model.QUserList;

/**
 *
 * @author Evgeniy Egorov
 */
public class BSlogistic implements IChangeCustomerStateEvent, ISelectNextService {

    private final static int D = 90;//если перерыв то на час
    private final static int S = 120;//если услуга не оказывается по расписанию

    private int noSchedule(long servId) {
        final QService service = QServiceTree.getInstance().getById(servId);
        if (service.getSchedule()==null) {
            return 0;
        }
        final GregorianCalendar gc = new GregorianCalendar();
        final int now = gc.get(GregorianCalendar.MINUTE) + gc.get(GregorianCalendar.HOUR_OF_DAY) * 60;
        final int dayM = gc.get(GregorianCalendar.DAY_OF_MONTH);
        int dayW = gc.get(GregorianCalendar.DAY_OF_WEEK);
        dayW = (dayW == 0 ? 7 : (dayW + 1));
        final Date s;
        final Date f;
        switch (service.getSchedule().getType()) {
            case 0://недельный
                switch (dayW) {
                    case 1:
                        s = service.getSchedule().getTime_begin_1();
                        f = service.getSchedule().getTime_end_1();
                        break;
                    case 2:
                        s = service.getSchedule().getTime_begin_2();
                        f = service.getSchedule().getTime_end_2();
                        break;
                    case 3:
                        s = service.getSchedule().getTime_begin_3();
                        f = service.getSchedule().getTime_end_3();
                        break;
                    case 4:
                        s = service.getSchedule().getTime_begin_4();
                        f = service.getSchedule().getTime_end_4();
                        break;
                    case 5:
                        s = service.getSchedule().getTime_begin_5();
                        f = service.getSchedule().getTime_end_5();
                        break;
                    case 6:
                        s = service.getSchedule().getTime_begin_6();
                        f = service.getSchedule().getTime_end_6();
                        break;
                    case 7:
                        s = service.getSchedule().getTime_begin_7();
                        f = service.getSchedule().getTime_end_7();
                        break;
                    default:
                        throw new AssertionError();
                }

                break;
            case 1://чет/нечет
                if (dayM % 2 == 0) {
                    s = service.getSchedule().getTime_begin_2();
                    f = service.getSchedule().getTime_end_2();
                } else {
                    s = service.getSchedule().getTime_begin_1();
                    f = service.getSchedule().getTime_end_1();
                }
                break;
            default:
                throw new AssertionError();
        }
        gc.setTime(s);
        final int ss = gc.get(GregorianCalendar.MINUTE) + gc.get(GregorianCalendar.HOUR_OF_DAY) * 60;
        gc.setTime(f);
        final int ff = gc.get(GregorianCalendar.MINUTE) + gc.get(GregorianCalendar.HOUR_OF_DAY) * 60;
        final boolean in = ss <= now && now < ff;
        return in ? 0 : S;
    }

    private int deltaPause(long servId) {
        int tot = 0;
        int nowork = 0;
        for (QUser user : QUserList.getInstance().getItems()) {
            for (QPlanService plan : user.getPlanServices()) {
                if (plan.getService().getId().equals(servId)) {
                    tot++;
                    if (user.isPause()) {
                        nowork++;
                    }
                    break;
                }
            }
        }
        float k = tot == 0 ? 1f : (nowork / tot);
        if (tot == 0) {
            QLog.l().logger().warn("Услуга ID=" + servId + " не обрабатывается ни одним юзером. А в нее поставлен посетитель!");
        }
        return Math.round(D * k);
    }

    /**
     * Сравнение времени ожидания двух услуг
     *
     * @param current
     * @param first
     * @param second
     * @return
     */
    private int longerThen(QService current, QService first, QService second) {
        // тут бы еще учесть паузу и количество юзеров с этой услугой
        final Integer cp = current == null ? 0 : current.getPoint();
        final int f = first.getCountCustomers() * first.getDuration()
                + (cp.equals(first.getPoint()) ? 0 : ServerProps.getInstance().getStandards().getRelocation())
                + deltaPause(first.getId()) + noSchedule(first.getId());
        final int s = second.getCountCustomers() * second.getDuration()
                + (cp.equals(second.getPoint()) ? 0 : ServerProps.getInstance().getStandards().getRelocation())
                + deltaPause(second.getId()) + noSchedule(first.getId());

        if (f > s) {
            return 1;
        } else {
            if (f < s) {
                return -1;
            } else {
                return 0;
            }
        }
    }

    @Override
    public void change(QCustomer customer, CustomerState newState, Long newServiceId) {
        if (customer.getState() == CustomerState.STATE_FINISH && customer.getComplexId() != null) {
            int len = 0;
            for (LinkedList<LinkedList<Long>> li : customer.getComplexId()) {
                len += li.size();
            }
            if (len != 0) {
                QLog.l().logger().debug("Дефолтная проводка по комплексным услугам. Осталось " + len);
                // ну и монипуляции с кастомером по переводу в выбранную услугу
                final QService nextServ = select(customer, customer.getService(), customer.getComplexId());
                customer.setStandTime(new Date());
                customer.setService(nextServ);
                nextServ.addCustomer(customer);
                customer.setState(CustomerState.STATE_WAIT_COMPLEX_SERVICE);
            }
        }

    }

    @Override
    public void change(String userPoint, String customerPrefix, int customerNumber, CustomerState cs) {
    }

    @Override
    public String getDescription() {
        return "Логика выбора следующей услуги при комплексном услужении. Т.е. если паровозом есть список услуг для прохождения.";
    }

    @Override
    public long getUID() {
        return 1l;
    }

    /**
     * Определение следующей услуги среди комплексного списка
     *
     * @param customer это тот чел, что идет по списку услуг.
     * @param before это та услуга, которая только что закончилась
     * @param setOfServices это список
     * @return
     */
    @Override
    public QService select(QCustomer customer, QService before, LinkedList<LinkedList<LinkedList<Long>>> setOfServices) {
        // найти самую незанятую услугу
        QService serv1 = null;

        int i = 0;
        for (LinkedList<LinkedList<Long>> ids : setOfServices) {

            QService serv = null;
            // Быстрейшая в списке одноранговых
            for (LinkedList<Long> id : ids) {
                final QService nextServ = QServiceTree.getInstance().getById(id.getFirst());
                if (nextServ == null || !resolvedDependences(id, setOfServices)) {
                    continue;
                }
                if (serv == null) {
                    serv = nextServ;
                } else {
                    // тут сравним где быстрее очередь подойдет
                    if (longerThen(before, serv, nextServ) > 0) {
                        serv = nextServ;
                    }
                }
            }

            if (i > 0) {
                if (serv != null) {
                    if (serv1 == null || longerThen(before, serv1, serv) > 0) {
                        serv1 = serv;
                    }
                    break; // из цикла по группам
                }
            } else {
                serv1 = serv;
            }

            i++;
        }
        if (serv1 != null) {
            QLog.l().logger().debug("Проводка по комплексным услугам BSlogistic. Кастомер '" + (customer == null ? "--" : customer.getFullNumber()) + "' в следующую услугу '" + serv1.getName() + "'");
            // удалить услугу из списка к которой уже стоим
            for (LinkedList<LinkedList<Long>> ids : setOfServices) {
                for (LinkedList<Long> id : ids) {
                    if (serv1.getId().equals(id.getFirst())) {
                        ids.remove(id);
                        break;
                    } else {
                        for (Long long1 : id) {
                            if (serv1.getId().equals(long1)) {
                                id.remove(long1);
                                break;
                            }
                        }
                    }
                }
            }
        } else {
            QLog.l().logger().error("Проводка по комплексным услугам BSlogistic. Не нашлось следующей услуги! Нонсенс!");
        }
        return serv1 == null ? null : QServiceTree.getInstance().getById(serv1.getId());
    }

    private boolean resolvedDependences(LinkedList<Long> deps, LinkedList<LinkedList<LinkedList<Long>>> setOfServices) {
        for (LinkedList<LinkedList<Long>> ids : setOfServices) {
            for (LinkedList<Long> id : ids) {
                for (Long dep : deps) {
                    if (!dep.equals(deps.getFirst()) && dep.equals(id.getFirst())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

}
